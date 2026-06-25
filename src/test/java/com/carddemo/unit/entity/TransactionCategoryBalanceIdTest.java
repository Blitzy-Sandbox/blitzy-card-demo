package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link TransactionCategoryBalanceId} composite key (COBOL CVTRA01Y
 * TRAN-CAT-KEY @ 27d6c6f): acctId (Long), typeCd (String CHAR(2)), catCd (Integer).
 * Validates @Embeddable + Serializable structure and the equals/hashCode contract over ALL key fields.
 */
@DisplayName("TransactionCategoryBalanceId - composite key (acctId, typeCd, catCd) [CVTRA01Y]")
class TransactionCategoryBalanceIdTest {

    private static TransactionCategoryBalanceId key(Long acctId, String typeCd, Integer catCd) {
        return new TransactionCategoryBalanceId(acctId, typeCd, catCd);
    }

    private static Field field(String name) throws NoSuchFieldException {
        return TransactionCategoryBalanceId.class.getDeclaredField(name);
    }

    @Test
    @DisplayName("@Embeddable annotation present and instances are Serializable")
    void structure() {
        assertThat(TransactionCategoryBalanceId.class.isAnnotationPresent(Embeddable.class)).isTrue();
        assertThat(key(1L, "01", 5)).isInstanceOf(Serializable.class);
    }

    @Test
    @DisplayName("field types: acctId=Long, typeCd=String, catCd=Integer (no numeric typeCd)")
    void fieldTypes() throws NoSuchFieldException {
        assertThat(field("acctId").getType()).isEqualTo(Long.class);
        assertThat(field("typeCd").getType()).isEqualTo(String.class);
        assertThat(field("catCd").getType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("typeCd maps to the SHORT column name 'type_cd' (NOT tran_type_cd) - unique to this table")
    void typeCdShortColumnName() throws NoSuchFieldException {
        Column column = field("typeCd").getAnnotation(Column.class);
        assertThat(column).as("@Column on typeCd").isNotNull();
        assertThat(column.name()).isEqualTo("type_cd");
    }

    @Test
    @DisplayName("equals is reflexive/symmetric/consistent and equal keys share hashCode")
    void equalsContract() {
        TransactionCategoryBalanceId a = key(1L, "01", 5);
        TransactionCategoryBalanceId b = key(1L, "01", 5);
        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("equals(null) and equals(otherType) are false")
    void equalsNullAndOtherType() {
        TransactionCategoryBalanceId a = key(1L, "01", 5);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-key");
    }

    @Test
    @DisplayName("keys are unequal when ANY of the 3 key fields differs")
    void unequalOnAnyFieldDiff() {
        TransactionCategoryBalanceId base = key(1L, "01", 5);
        assertThat(base).isNotEqualTo(key(2L, "01", 5));
        assertThat(base).isNotEqualTo(key(1L, "02", 5));
        assertThat(base).isNotEqualTo(key(1L, "01", 6));
    }
}
