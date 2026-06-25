package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link TransactionCategory} reference entity (COBOL CVTRA04Y TRAN-CAT-RECORD @ 27d6c6f):
 * composite @EmbeddedId key (tranTypeCd + tranCatCd) + the VARCHAR(50) description.
 */
@DisplayName("TransactionCategory entity - CVTRA04Y (60B): @EmbeddedId + VARCHAR(50) desc, NO @Version")
class TransactionCategoryTest {

    private static Field field(String name) throws NoSuchFieldException {
        return TransactionCategory.class.getDeclaredField(name);
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
    @DisplayName("no field is double/float")
    void noFloatingPointFields() {
        assertNoFloatingPointFields(TransactionCategory.class);
    }

    @Test
    @DisplayName("has NO @Version field")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(TransactionCategory.class)).isFalse();
    }

    @Test
    @DisplayName("id is an @EmbeddedId of type TransactionCategoryId")
    void embeddedId() throws NoSuchFieldException {
        Field id = field("id");
        assertThat(id.isAnnotationPresent(EmbeddedId.class)).isTrue();
        assertThat(id.getType()).isEqualTo(TransactionCategoryId.class);
    }

    @Test
    @DisplayName("tranCatTypeDesc is String VARCHAR(50) on column tran_cat_type_desc")
    void description() throws NoSuchFieldException {
        Field f = field("tranCatTypeDesc");
        assertThat(f.getType()).isEqualTo(String.class);
        Column column = f.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("tran_cat_type_desc");
        assertThat(column.length()).isEqualTo(50);
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (embedded id only)")
    void equalsAndHashCodeOverIdentity() {
        TransactionCategory a = new TransactionCategory();
        a.setId(new TransactionCategoryId("01", 5));
        TransactionCategory b = new TransactionCategory();
        b.setId(new TransactionCategoryId("01", 5));
        b.setTranCatTypeDesc("Regular Sales Draft");
        TransactionCategory other = new TransactionCategory();
        other.setId(new TransactionCategoryId("01", 6));

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-category");
        assertThat(a.toString()).isNotNull();
    }
}
