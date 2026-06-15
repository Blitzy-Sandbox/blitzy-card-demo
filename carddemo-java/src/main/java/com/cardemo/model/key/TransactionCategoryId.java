package com.cardemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code TransactionCategory} entity.
 *
 * <p>This {@link Embeddable} value type is the Java 25 / Spring Data JPA
 * replacement for the multi-field VSAM KSDS key that the legacy AWS CardDemo
 * mainframe application used to address rows of the transaction-category
 * (reference data) dataset {@code TRANCATG}. It is migrated from the COBOL
 * copybook {@code app/cpy/CVTRA04Y.cpy}, specifically the {@code TRAN-CAT-KEY}
 * group nested inside {@code TRAN-CAT-RECORD} (record length 60):</p>
 *
 * <pre>{@code
 * 05  TRAN-CAT-KEY.
 *    10 TRAN-TYPE-CD   PIC X(02).
 *    10 TRAN-CAT-CD    PIC 9(04).
 * }</pre>
 *
 * <h2>VSAM KSDS key &rarr; JPA composite key substitution</h2>
 * <p>On the mainframe, {@code TRANCATG} was a keyed (KSDS) VSAM cluster whose
 * primary key was the concatenation of the two {@code TRAN-CAT-KEY} sub-fields,
 * read positionally as a single contiguous byte string. The relational target
 * replaces that physical concatenated key with an explicit, typed composite
 * key: this class is declared on the owning entity as
 * {@code @EmbeddedId private TransactionCategoryId id;}, and Hibernate maps each
 * component to its own column. This is the technology-substitution point for the
 * dataset's access path; per the migration's <strong>Minimal Change Clause</strong>
 * (&sect;0.7.1) the key's field order, lengths and semantics are preserved
 * <strong>exactly</strong>, no business logic is added, and no field beyond the
 * two original key components is introduced.</p>
 *
 * <h2>Scope of this key class</h2>
 * <p>Only the two {@code TRAN-CAT-KEY} components live here. The record's
 * remaining, non-key fields &mdash; {@code TRAN-CAT-TYPE-DESC PIC X(50)} (the
 * 50-character category description) and the trailing {@code FILLER PIC X(04)}
 * &mdash; are deliberately <strong>excluded</strong>; they belong to the
 * {@code TransactionCategory} entity, not to its identifier. Because neither key
 * component carries decimal positions, this class uses only exact integral and
 * character types and intentionally contains <strong>no</strong> {@code float},
 * {@code double} or {@code BigDecimal}.</p>
 *
 * <h2>Relationship to {@code TransactionCategoryBalanceId}</h2>
 * <p>This key shares its two component names ({@code typeCode} + {@code catCode})
 * with {@link TransactionCategoryBalanceId}, yet the two are intentionally kept
 * as <strong>separate, standalone</strong> classes with <strong>no</strong>
 * inheritance between them. They identify different datasets
 * ({@code TRANCATG} reference data here versus {@code TCATBALF} balances there),
 * their key arity differs (this key has two components; the balance key adds a
 * leading {@code acctId}), and coupling them through a shared base type would
 * introduce an artificial relationship that the legacy record layouts do not
 * have. Keeping each key simple and self-contained honours the Minimal Change
 * Clause.</p>
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
 *       {@link #hashCode()} cover both components so the key behaves correctly
 *       as a map key and inside the Hibernate persistence context.</li>
 *   <li><strong>No validation annotations.</strong> Jakarta Bean Validation
 *       constraints (for example {@code @NotNull}/{@code @Size}) are
 *       intentionally absent here; input validation is the responsibility of
 *       the request DTO layer, keeping this a pure persistence key type.</li>
 * </ul>
 *
 * <h2>Column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code type_code} and
 * {@code category_code} &mdash; are the authoritative physical column names for
 * the composite key. The {@code TransactionCategory} entity's
 * {@code transaction_categories} table mapping and the Flyway
 * {@code V1__create_schema.sql} DDL must use these exact names, with
 * {@code type_code} as {@code CHAR}/{@code VARCHAR(2)} and {@code category_code}
 * as {@code INTEGER}.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see java.io.Serializable
 */
@Embeddable
public class TransactionCategoryId implements Serializable {

    /**
     * Serialization version identifier. JPA mandates that a primary-key type be
     * {@link Serializable}; declaring an explicit {@code serialVersionUID} pins
     * the serialized form of this key.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction-type-code component of the key.
     *
     * <p>Migrated from {@code TRAN-TYPE-CD PIC X(02)} &mdash; a fixed-length
     * 2-character alphanumeric field, modelled as a {@link String} of length 2.</p>
     */
    // TRAN-TYPE-CD PIC X(02) -> fixed-length 2-char alphanumeric -> String(2)
    @Column(name = "type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction-category-code component of the key.
     *
     * <p>Migrated from {@code TRAN-CAT-CD PIC 9(04)} &mdash; a 4-digit integer
     * (maximum value 9999). This fits comfortably within an {@link Integer}, the
     * appropriate exact integral type for the value range (per &sect;0.7.3 of the
     * migration plan). The field has no decimal positions, so no
     * {@code BigDecimal} is used.</p>
     */
    // TRAN-CAT-CD PIC 9(04) -> 4-digit integer (max 9999) -> Integer
    @Column(name = "category_code", nullable = false)
    private Integer catCode;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate)
     * to instantiate the embedded identifier reflectively.
     */
    public TransactionCategoryId() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Constructs a fully-populated composite key.
     *
     * <p>Parameter order mirrors the COBOL {@code TRAN-CAT-KEY} field order
     * exactly: transaction-type code, then transaction-category code.</p>
     *
     * @param typeCode transaction-type code ({@code TRAN-TYPE-CD})
     * @param catCode  transaction-category code ({@code TRAN-CAT-CD})
     */
    public TransactionCategoryId(String typeCode, Integer catCode) {
        this.typeCode = typeCode;
        this.catCode = catCode;
    }

    /**
     * Returns the transaction-type-code component ({@code TRAN-TYPE-CD}).
     *
     * @return the 2-character transaction-type code, or {@code null} if unset
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction-type-code component ({@code TRAN-TYPE-CD}).
     *
     * @param typeCode the 2-character transaction-type code to set
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction-category-code component ({@code TRAN-CAT-CD}).
     *
     * @return the transaction-category code, or {@code null} if unset
     */
    public Integer getCatCode() {
        return catCode;
    }

    /**
     * Sets the transaction-category-code component ({@code TRAN-CAT-CD}).
     *
     * @param catCode the transaction-category code to set
     */
    public void setCatCode(Integer catCode) {
        this.catCode = catCode;
    }

    /**
     * Value-based equality across both key components.
     *
     * <p>Two instances are equal only when {@code o} is exactly a
     * {@code TransactionCategoryId} and its {@code typeCode} and {@code catCode}
     * both match. Exact-class comparison (rather than {@code instanceof}) is used
     * because this is an identity type: a subtype must not be considered equal to
     * its base key.</p>
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
        TransactionCategoryId that = (TransactionCategoryId) o;
        return Objects.equals(typeCode, that.typeCode)
                && Objects.equals(catCode, that.catCode);
    }

    /**
     * Hash code derived from both key components, consistent with
     * {@link #equals(Object)}.
     *
     * @return the composite hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(typeCode, catCode);
    }

    /**
     * Diagnostic representation including both key components.
     *
     * @return a human-readable description of this composite key
     */
    @Override
    public String toString() {
        return "TransactionCategoryId{"
                + "typeCode='" + typeCode + '\''
                + ", catCode=" + catCode
                + '}';
    }
}
