package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link TransactionCategoryBalance} entity (COBOL CVTRA01Y TRAN-CAT-BAL-RECORD @ 27d6c6f):
 * composite @EmbeddedId key + the running BigDecimal balance tranCatBal NUMERIC(11,2).
 */
@DisplayName("TransactionCategoryBalance entity - CVTRA01Y: @EmbeddedId + tranCatBal NUMERIC(11,2), NO @Version")
class TransactionCategoryBalanceTest {

    private static Field field(String name) throws NoSuchFieldException {
        return TransactionCategoryBalance.class.getDeclaredField(name);
    }

    private static boolean hasVersionAnnotation(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (f.isAnnotationPresent(Version.class)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoFloatingPointFields(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            assertThat(f.getType())
                    .as("field '%s' must not be floating-point (decimal exactness, AAP 0.6.1)", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    @Test
    @DisplayName("no field is double/float - tranCatBal is BigDecimal")
    void noFloatingPointFields() {
        assertNoFloatingPointFields(TransactionCategoryBalance.class);
    }

    @Test
    @DisplayName("has NO @Version field")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(TransactionCategoryBalance.class)).isFalse();
    }

    @Test
    @DisplayName("id is an @EmbeddedId of type TransactionCategoryBalanceId")
    void embeddedId() throws NoSuchFieldException {
        Field id = field("id");
        assertThat(id.isAnnotationPresent(EmbeddedId.class)).isTrue();
        assertThat(id.getType()).isEqualTo(TransactionCategoryBalanceId.class);
    }

    @Test
    @DisplayName("tranCatBal is BigDecimal NUMERIC(11,2)")
    void tranCatBalIsBigDecimal() throws NoSuchFieldException {
        Field f = field("tranCatBal");
        assertThat(f.getType()).isEqualTo(BigDecimal.class);
        Column column = f.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("tran_cat_bal");
        assertThat(column.precision()).isEqualTo(11);
        assertThat(column.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (embedded id only)")
    void equalsAndHashCodeOverIdentity() {
        TransactionCategoryBalance a = new TransactionCategoryBalance();
        a.setId(new TransactionCategoryBalanceId(1L, "01", 5));
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setId(new TransactionCategoryBalanceId(1L, "01", 5));
        b.setTranCatBal(new BigDecimal("12.34"));
        TransactionCategoryBalance other = new TransactionCategoryBalance();
        other.setId(new TransactionCategoryBalanceId(2L, "01", 5));

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-balance");
        assertThat(a.toString()).isNotNull();
    }
}
