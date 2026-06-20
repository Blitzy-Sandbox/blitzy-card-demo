package com.carddemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for disclosure-group reference data.
 *
 * <p>This embeddable key is used as the {@code @EmbeddedId} of the
 * {@code DisclosureGroup} entity and as the identifier type of
 * {@code DisclosureGroupRepository}
 * ({@code JpaRepository<DisclosureGroup, DisclosureGroupId>}). It holds the
 * three key components: the account group id, the transaction type code, and
 * the transaction category code. The account group id is a {@code String} so
 * that alphanumeric and {@code DEFAULT} group values are preserved exactly.</p>
 *
 * <p>Source lineage preserved via commit {@code 27d6c6f}.</p>
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

    public DisclosureGroupId() {
    }

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
