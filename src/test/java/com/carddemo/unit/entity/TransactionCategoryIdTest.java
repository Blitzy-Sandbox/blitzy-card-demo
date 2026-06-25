package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link TransactionCategoryId} composite key (COBOL CVTRA04Y TRAN-CAT-KEY @ 27d6c6f):
 * tranTypeCd (String CHAR(2)), tranCatCd (Integer).
 */
@DisplayName("TransactionCategoryId - composite key (tranTypeCd, tranCatCd) [CVTRA04Y]")
class TransactionCategoryIdTest {

    private static TransactionCategoryId key(String tranTypeCd, Integer tranCatCd) {
        return new TransactionCategoryId(tranTypeCd, tranCatCd);
    }

    private static Field field(String name) throws NoSuchFieldException {
        return TransactionCategoryId.class.getDeclaredField(name);
    }

    @Test
    @DisplayName("@Embeddable annotation present and instances are Serializable")
    void structure() {
        assertThat(TransactionCategoryId.class.isAnnotationPresent(Embeddable.class)).isTrue();
        assertThat(key("01", 5)).isInstanceOf(Serializable.class);
    }

    @Test
    @DisplayName("field types: tranTypeCd=String, tranCatCd=Integer")
    void fieldTypes() throws NoSuchFieldException {
        assertThat(field("tranTypeCd").getType()).isEqualTo(String.class);
        assertThat(field("tranCatCd").getType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("column names: tran_type_cd, tran_cat_cd")
    void columnNames() throws NoSuchFieldException {
        assertThat(field("tranTypeCd").getAnnotation(Column.class).name()).isEqualTo("tran_type_cd");
        assertThat(field("tranCatCd").getAnnotation(Column.class).name()).isEqualTo("tran_cat_cd");
    }

    @Test
    @DisplayName("equals is reflexive/symmetric and equal keys share hashCode")
    void equalsContract() {
        TransactionCategoryId a = key("01", 5);
        TransactionCategoryId b = key("01", 5);
        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("equals(null) and equals(otherType) are false")
    void equalsNullAndOtherType() {
        TransactionCategoryId a = key("01", 5);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-key");
    }

    @Test
    @DisplayName("keys are unequal when EITHER key field differs")
    void unequalOnAnyFieldDiff() {
        TransactionCategoryId base = key("01", 5);
        assertThat(base).isNotEqualTo(key("02", 5));
        assertThat(base).isNotEqualTo(key("01", 6));
    }
}
