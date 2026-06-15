package com.cardemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code DisclosureGroup} entity.
 *
 * <p>This {@link Embeddable} value type is the Java 25 / Spring Data JPA
 * replacement for the multi-field VSAM KSDS key that the legacy AWS CardDemo
 * mainframe application used to address rows of the disclosure-group dataset
 * {@code DISCGRP}. It is migrated from the COBOL copybook
 * {@code app/cpy/CVTRA02Y.cpy}, specifically the {@code DIS-GROUP-KEY} group
 * nested inside {@code DIS-GROUP-RECORD} (record length 50):</p>
 *
 * <pre>{@code
 * 05  DIS-GROUP-KEY.
 *    10 DIS-ACCT-GROUP-ID   PIC X(10).
 *    10 DIS-TRAN-TYPE-CD    PIC X(02).
 *    10 DIS-TRAN-CAT-CD     PIC 9(04).
 * }</pre>
 *
 * <h2>VSAM KSDS key &rarr; JPA composite key substitution</h2>
 * <p>On the mainframe, {@code DISCGRP} was a keyed (KSDS) VSAM cluster whose
 * primary key was the concatenation of the three {@code DIS-GROUP-KEY}
 * sub-fields, read positionally as a single contiguous byte string. The
 * relational target replaces that physical concatenated key with an explicit,
 * typed composite key: this class is declared on the owning entity as
 * {@code @EmbeddedId private DisclosureGroupId id;}, and Hibernate maps each
 * component to its own column. This is the technology-substitution point for
 * the dataset's access path; per the migration's
 * <strong>Minimal Change Clause</strong> (&sect;0.7.1) the key's field order,
 * lengths and semantics are preserved <strong>exactly</strong>, no business
 * logic is added, and no field beyond the three original key components is
 * introduced.</p>
 *
 * <h2>Scope of this key class</h2>
 * <p>Only the three {@code DIS-GROUP-KEY} components live here. The record's
 * remaining, non-key fields &mdash; {@code DIS-INT-RATE PIC S9(04)V99} (the
 * signed decimal interest rate, which becomes a {@code java.math.BigDecimal}
 * with scale 2 on the entity) and the trailing {@code FILLER PIC X(28)} &mdash;
 * are deliberately <strong>excluded</strong>; they belong to the
 * {@code DisclosureGroup} entity, not to its identifier. Because none of the
 * three key components carries decimal positions, this class uses only exact
 * integral and character types and intentionally contains <strong>no</strong>
 * {@code float}, {@code double} or {@code BigDecimal}.</p>
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
 * <p>The {@link Column} names declared below &mdash; {@code account_group_id},
 * {@code transaction_type_code} and {@code transaction_category_code} &mdash;
 * match the authoritative physical column names for the composite key. The
 * {@code DisclosureGroup} entity's {@code disclosure_group} table mapping and the
 * Flyway {@code V1__create_schema.sql} DDL declare these exact names, with
 * {@code account_group_id} as {@code VARCHAR(10)}, {@code transaction_type_code} as
 * {@code CHAR}/{@code VARCHAR(2)} and {@code transaction_category_code} as
 * {@code INTEGER}.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see java.io.Serializable
 */
@Embeddable
public class DisclosureGroupId implements Serializable {

    /**
     * Serialization version identifier. JPA mandates that a primary-key type be
     * {@link Serializable}; declaring an explicit {@code serialVersionUID} pins
     * the serialized form of this key.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Account-group identifier component of the key.
     *
     * <p>Migrated from {@code DIS-ACCT-GROUP-ID PIC X(10)} &mdash; a
     * fixed-length 10-character alphanumeric field, modelled as a
     * {@link String} of length 10. Semantically this is the same 10-character
     * disclosure group code as {@code ACCT-GROUP-ID PIC X(10)} carried on the
     * {@code Account} record, so a character type (not a numeric type) is the
     * faithful mapping.</p>
     */
    // DIS-ACCT-GROUP-ID PIC X(10) -> fixed-length 10-char alphanumeric -> String(10)
    @Column(name = "account_group_id", length = 10, nullable = false)
    private String groupId;

    /**
     * Transaction-type-code component of the key.
     *
     * <p>Migrated from {@code DIS-TRAN-TYPE-CD PIC X(02)} &mdash; a fixed-length
     * 2-character alphanumeric field, modelled as a {@link String} of length 2.</p>
     */
    // DIS-TRAN-TYPE-CD PIC X(02) -> fixed-length 2-char alphanumeric -> String(2)
    @Column(name = "transaction_type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction-category-code component of the key.
     *
     * <p>Migrated from {@code DIS-TRAN-CAT-CD PIC 9(04)} &mdash; a 4-digit
     * integer (maximum value 9999). This fits comfortably within an
     * {@link Integer}, the appropriate exact integral type for the value range
     * (per &sect;0.7.3 of the migration plan). The field has no decimal
     * positions, so no {@code BigDecimal} is used.</p>
     */
    // DIS-TRAN-CAT-CD PIC 9(04) -> 4-digit integer (max 9999) -> Integer
    @Column(name = "transaction_category_code", nullable = false)
    private Integer catCode;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate)
     * to instantiate the embedded identifier reflectively.
     */
    public DisclosureGroupId() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Constructs a fully-populated composite key.
     *
     * <p>Parameter order mirrors the COBOL {@code DIS-GROUP-KEY} field order
     * exactly: account-group id, then transaction-type code, then
     * transaction-category code.</p>
     *
     * @param groupId  account-group identifier ({@code DIS-ACCT-GROUP-ID})
     * @param typeCode transaction-type code ({@code DIS-TRAN-TYPE-CD})
     * @param catCode  transaction-category code ({@code DIS-TRAN-CAT-CD})
     */
    public DisclosureGroupId(String groupId, String typeCode, Integer catCode) {
        this.groupId = groupId;
        this.typeCode = typeCode;
        this.catCode = catCode;
    }

    /**
     * Returns the account-group-identifier component ({@code DIS-ACCT-GROUP-ID}).
     *
     * @return the 10-character account-group identifier, or {@code null} if unset
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the account-group-identifier component ({@code DIS-ACCT-GROUP-ID}).
     *
     * @param groupId the 10-character account-group identifier to set
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Returns the transaction-type-code component ({@code DIS-TRAN-TYPE-CD}).
     *
     * @return the 2-character transaction-type code, or {@code null} if unset
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction-type-code component ({@code DIS-TRAN-TYPE-CD}).
     *
     * @param typeCode the 2-character transaction-type code to set
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction-category-code component ({@code DIS-TRAN-CAT-CD}).
     *
     * @return the transaction-category code, or {@code null} if unset
     */
    public Integer getCatCode() {
        return catCode;
    }

    /**
     * Sets the transaction-category-code component ({@code DIS-TRAN-CAT-CD}).
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
     * {@code DisclosureGroupId} and its {@code groupId}, {@code typeCode} and
     * {@code catCode} all match. Exact-class comparison (rather than
     * {@code instanceof}) is used because this is an identity type: a subtype
     * must not be considered equal to its base key.</p>
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
        DisclosureGroupId that = (DisclosureGroupId) o;
        return Objects.equals(groupId, that.groupId)
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
        return Objects.hash(groupId, typeCode, catCode);
    }

    /**
     * Diagnostic representation including all three key components.
     *
     * @return a human-readable description of this composite key
     */
    @Override
    public String toString() {
        return "DisclosureGroupId{"
                + "groupId='" + groupId + '\''
                + ", typeCode='" + typeCode + '\''
                + ", catCode=" + catCode
                + '}';
    }
}
