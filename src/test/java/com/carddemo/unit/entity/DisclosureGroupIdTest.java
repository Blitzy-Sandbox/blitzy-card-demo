package com.carddemo.unit.entity;

import com.carddemo.entity.DisclosureGroupId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link DisclosureGroupId} composite key (COBOL CVTRA02Y DIS-GROUP-KEY @ 27d6c6f):
 * acctGroupId (String CHAR(10)), tranTypeCd (String CHAR(2)), tranCatCd (Integer).
 */
@DisplayName("DisclosureGroupId - composite key (acctGroupId, tranTypeCd, tranCatCd) [CVTRA02Y]")
class DisclosureGroupIdTest {

    private static DisclosureGroupId key(String acctGroupId, String tranTypeCd, Integer tranCatCd) {
        return new DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd);
    }

    private static Field field(String name) throws NoSuchFieldException {
        return DisclosureGroupId.class.getDeclaredField(name);
    }

    @Test
    @DisplayName("@Embeddable annotation present and instances are Serializable")
    void structure() {
        assertThat(DisclosureGroupId.class.isAnnotationPresent(Embeddable.class)).isTrue();
        assertThat(key("DEFAULT   ", "01", 5)).isInstanceOf(Serializable.class);
    }

    @Test
    @DisplayName("field types: acctGroupId=String, tranTypeCd=String, tranCatCd=Integer")
    void fieldTypes() throws NoSuchFieldException {
        assertThat(field("acctGroupId").getType()).isEqualTo(String.class);
        assertThat(field("tranTypeCd").getType()).isEqualTo(String.class);
        assertThat(field("tranCatCd").getType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("column names: acct_group_id, tran_type_cd, tran_cat_cd")
    void columnNames() throws NoSuchFieldException {
        assertThat(field("acctGroupId").getAnnotation(Column.class).name()).isEqualTo("acct_group_id");
        assertThat(field("tranTypeCd").getAnnotation(Column.class).name()).isEqualTo("tran_type_cd");
        assertThat(field("tranCatCd").getAnnotation(Column.class).name()).isEqualTo("tran_cat_cd");
    }

    @Test
    @DisplayName("equals is reflexive/symmetric and equal keys share hashCode")
    void equalsContract() {
        DisclosureGroupId a = key("DEFAULT   ", "01", 5);
        DisclosureGroupId b = key("DEFAULT   ", "01", 5);
        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("equals(null) and equals(otherType) are false")
    void equalsNullAndOtherType() {
        DisclosureGroupId a = key("DEFAULT   ", "01", 5);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-key");
    }

    @Test
    @DisplayName("keys are unequal when ANY of the 3 key fields differs")
    void unequalOnAnyFieldDiff() {
        DisclosureGroupId base = key("DEFAULT   ", "01", 5);
        assertThat(base).isNotEqualTo(key("OTHER     ", "01", 5));
        assertThat(base).isNotEqualTo(key("DEFAULT   ", "02", 5));
        assertThat(base).isNotEqualTo(key("DEFAULT   ", "01", 6));
    }
}
