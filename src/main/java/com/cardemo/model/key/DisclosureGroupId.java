package com.cardemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * JPA {@link Embeddable} composite primary key for the {@code DisclosureGroup} entity.
 *
 * <p>This class is a direct translation of the COBOL {@code DIS-GROUP-KEY} group-level
 * key definition from {@code app/cpy/CVTRA02Y.cpy}. The disclosure group record
 * (RECLN = 50 bytes) uses a 16-byte composite key consisting of three fields:</p>
 *
 * <pre>
 *   05  DIS-GROUP-KEY.
 *      10 DIS-ACCT-GROUP-ID   PIC X(10).   → groupId  (10 chars)
 *      10 DIS-TRAN-TYPE-CD    PIC X(02).   → typeCode ( 2 chars)
 *      10 DIS-TRAN-CAT-CD     PIC 9(04).   → catCode  ( 4 chars)
 * </pre>
 *
 * <p>All three fields are stored as {@code String} to preserve exact COBOL byte-level
 * semantics. In particular, {@code DIS-TRAN-CAT-CD PIC 9(04)} is a numeric display
 * field that retains leading zeros (e.g., {@code "0005"}) — mapping to {@code int}
 * would lose that precision.</p>
 *
 * <p>The {@code equals()} and {@code hashCode()} methods are critical for JPA entity
 * identity and for the DEFAULT group fallback lookup logic used during interest
 * calculation in the batch pipeline.</p>
 *
 * @see com.cardemo.model.entity.DisclosureGroup
 */
@Embeddable
public class DisclosureGroupId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Disclosure account group identifier.
     * Maps from COBOL {@code DIS-ACCT-GROUP-ID PIC X(10)}.
     * Examples: {@code "0000000001"}, {@code "DEFAULT   "}.
     */
    @Column(name = "dis_acct_group_id", length = 10, nullable = false)
    private String groupId;

    /**
     * Transaction type code within the disclosure group.
     * Maps from COBOL {@code DIS-TRAN-TYPE-CD PIC X(02)}.
     * Examples: {@code "01"}, {@code "02"}.
     */
    @Column(name = "dis_tran_type_cd", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction category code within the disclosure group.
     * Maps from COBOL {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     * Stored as String to preserve leading zeros (e.g., {@code "0005"}).
     */
    @Column(name = "dis_tran_cat_cd", length = 4, nullable = false)
    private String catCode;

    /**
     * No-args constructor required by the JPA specification for embeddable classes.
     */
    public DisclosureGroupId() {
        // Required by JPA
    }

    /**
     * Constructs a fully populated composite key for a disclosure group record.
     *
     * @param groupId  the account group identifier (up to 10 characters)
     * @param typeCode the transaction type code (up to 2 characters)
     * @param catCode  the transaction category code (up to 4 characters, numeric display)
     */
    public DisclosureGroupId(String groupId, String typeCode, String catCode) {
        this.groupId = groupId;
        this.typeCode = typeCode;
        this.catCode = catCode;
    }

    /**
     * Returns the disclosure account group identifier.
     *
     * @return the group ID (up to 10 characters)
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the disclosure account group identifier.
     *
     * @param groupId the group ID to set (up to 10 characters)
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return the type code (up to 2 characters)
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code.
     *
     * @param typeCode the type code to set (up to 2 characters)
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the category code (up to 4 characters, numeric display preserving leading zeros)
     */
    public String getCatCode() {
        return catCode;
    }

    /**
     * Sets the transaction category code.
     *
     * @param catCode the category code to set (up to 4 characters, numeric display)
     */
    public void setCatCode(String catCode) {
        this.catCode = catCode;
    }

    /**
     * Compares this composite key with another object for equality.
     *
     * <p>Two {@code DisclosureGroupId} instances are equal if and only if all three
     * fields ({@code groupId}, {@code typeCode}, {@code catCode}) are equal. This is
     * critical for JPA entity identity resolution, Hibernate first-level cache lookups,
     * and the DEFAULT group fallback logic in the interest calculation batch pipeline.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the given object represents the same composite key
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
     * Computes a hash code based on all three composite key fields.
     *
     * <p>Consistent with {@link #equals(Object)} — uses {@code groupId},
     * {@code typeCode}, and {@code catCode}.</p>
     *
     * @return the hash code value for this composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(groupId, typeCode, catCode);
    }

    /**
     * Returns a human-readable string representation of this composite key.
     *
     * @return a string in the format
     *         {@code DisclosureGroupId{groupId='...', typeCode='...', catCode='...'}}
     */
    @Override
    public String toString() {
        return "DisclosureGroupId{"
                + "groupId='" + groupId + '\''
                + ", typeCode='" + typeCode + '\''
                + ", catCode='" + catCode + '\''
                + '}';
    }
}
