package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link TransactionType} reference entity (COBOL CVTRA03Y TRAN-TYPE-RECORD @ 27d6c6f).
 * Key parity: the entity persists the raw 2-char code as a plain String tranType (CHAR(2)); the typed
 * TransactionTypeCode enum + converter are used ONLY on the Transaction entity, never here.
 */
@DisplayName("TransactionType entity - CVTRA03Y (60B): plain-String tranType CHAR(2) PK (NOT enum), NO @Version")
class TransactionTypeTest {

    private static Field field(String name) throws NoSuchFieldException {
        return TransactionType.class.getDeclaredField(name);
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
        assertNoFloatingPointFields(TransactionType.class);
    }

    @Test
    @DisplayName("has NO @Version field")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(TransactionType.class)).isFalse();
    }

    @Test
    @DisplayName("primary key tranType is @Id plain String (NOT enum) mapped to char(2) column tran_type")
    void primaryKeyIsPlainString() throws NoSuchFieldException {
        Field pk = field("tranType");
        assertThat(pk.isAnnotationPresent(Id.class)).isTrue();
        assertThat(pk.getType()).as("tranType must be a plain String, never the enum").isEqualTo(String.class);
        Column column = pk.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("tran_type");
        assertThat(column.length()).isEqualTo(2);
    }

    @Test
    @DisplayName("tranTypeDesc is String VARCHAR(50) on column tran_type_desc")
    void description() throws NoSuchFieldException {
        Field f = field("tranTypeDesc");
        assertThat(f.getType()).isEqualTo(String.class);
        Column column = f.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("tran_type_desc");
        assertThat(column.length()).isEqualTo(50);
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (tranType only)")
    void equalsAndHashCodeOverIdentity() {
        TransactionType a = new TransactionType();
        a.setTranType("01");
        TransactionType b = new TransactionType();
        b.setTranType("01");
        b.setTranTypeDesc("Purchase");
        TransactionType other = new TransactionType();
        other.setTranType("02");

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-transaction-type");
        assertThat(a.toString()).isNotNull();
    }
}
