package com.carddemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite primary key for the disclosure-group reference data.
 *
 * <p>This {@link Embeddable} key combines the three components that uniquely
 * identify a disclosure-group row: the account group identifier, the
 * transaction type code, and the transaction category code. It is used by the
 * {@code com.carddemo.model.entity.DisclosureGroup} entity through an
 * {@code @EmbeddedId} mapping and serves as the identifier type for
 * {@code com.carddemo.repository.DisclosureGroupRepository}.</p>
 *
 * <p>Traceability: source commit {@code 27d6c6f}.</p>
 */
@Embeddable
public class DisclosureGroupId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "dis_acct_group_id", nullable = false, length = 10)
    private String accountGroupId;

    @Column(name = "dis_tran_type_cd", nullable = false, length = 2)
    private String transactionTypeCode;

    @Column(name = "dis_tran_cat_cd", nullable = false)
    private Integer transactionCategoryCode;

    /**
     * Creates an empty key. Required by the JPA specification for
     * {@code @Embeddable} types.
     */
    public DisclosureGroupId() {
    }

    /**
     * Creates a fully populated disclosure-group key.
     *
     * @param accountGroupId          the account group identifier (max length 10)
     * @param transactionTypeCode     the transaction type code (max length 2)
     * @param transactionCategoryCode the four-digit transaction category code
     */
    public DisclosureGroupId(String accountGroupId, String transactionTypeCode, Integer transactionCategoryCode) {
        this.accountGroupId = accountGroupId;
        this.transactionTypeCode = transactionTypeCode;
        this.transactionCategoryCode = transactionCategoryCode;
    }

    public String getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(String accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public String getTransactionTypeCode() {
        return transactionTypeCode;
    }

    public void setTransactionTypeCode(String transactionTypeCode) {
        this.transactionTypeCode = transactionTypeCode;
    }

    public Integer getTransactionCategoryCode() {
        return transactionCategoryCode;
    }

    public void setTransactionCategoryCode(Integer transactionCategoryCode) {
        this.transactionCategoryCode = transactionCategoryCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DisclosureGroupId that = (DisclosureGroupId) o;
        return Objects.equals(accountGroupId, that.accountGroupId)
                && Objects.equals(transactionTypeCode, that.transactionTypeCode)
                && Objects.equals(transactionCategoryCode, that.transactionCategoryCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountGroupId, transactionTypeCode, transactionCategoryCode);
    }

    @Override
    public String toString() {
        return "DisclosureGroupId{"
                + "accountGroupId='" + accountGroupId + '\''
                + ", transactionTypeCode='" + transactionTypeCode + '\''
                + ", transactionCategoryCode=" + transactionCategoryCode
                + '}';
    }
}
