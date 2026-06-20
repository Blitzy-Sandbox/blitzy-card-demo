package com.carddemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the transaction-category-balance record.
 *
 * <p>Embeddable JPA identity made up of the account identifier, the
 * transaction type code, and the transaction category code. Instances are used
 * as the {@code @EmbeddedId} of the {@code TransactionCategoryBalance} entity
 * and as the identifier type of its Spring Data repository.</p>
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "trancat_acct_id", nullable = false)
    private Long accountId;

    @Column(name = "trancat_type_cd", nullable = false, length = 2)
    private String typeCode;

    @Column(name = "trancat_cd", nullable = false)
    private Integer categoryCode;

    public TransactionCategoryBalanceId() {
    }

    public TransactionCategoryBalanceId(Long accountId, String typeCode, Integer categoryCode) {
        this.accountId = accountId;
        this.typeCode = typeCode;
        this.categoryCode = categoryCode;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public Integer getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(typeCode, that.typeCode)
                && Objects.equals(categoryCode, that.categoryCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, typeCode, categoryCode);
    }

    @Override
    public String toString() {
        return "TransactionCategoryBalanceId{"
                + "accountId=" + accountId
                + ", typeCode='" + typeCode + '\''
                + ", categoryCode=" + categoryCode
                + '}';
    }
}
