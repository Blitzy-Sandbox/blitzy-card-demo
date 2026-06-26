package com.carddemo.unit.entity;

import com.carddemo.entity.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Transaction} entity (COBOL CVTRA05Y TRAN-RECORD, RECLN 350 @ 27d6c6f).
 * Key parity: tranTypeCd is a raw {@link String} mapped to a {@code char(2)} column with NO attribute
 * converter, preserving the open COBOL {@code PIC X(2)} write-through semantics (an unknown code such as
 * {@code "99"} round-trips verbatim); origTs/procTs are 26-char text timestamps, NOT temporal types.
 */
@DisplayName("Transaction entity - CVTRA05Y (350B): raw String tranTypeCd (no converter), NO @Version")
class TransactionTest {

    private static Field field(String name) throws NoSuchFieldException {
        return Transaction.class.getDeclaredField(name);
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
    @DisplayName("no field is double/float - tranAmt is BigDecimal")
    void noFloatingPointFields() {
        assertNoFloatingPointFields(Transaction.class);
    }

    @Test
    @DisplayName("has NO @Version field")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(Transaction.class)).as("Transaction must NOT carry @Version").isFalse();
    }

    @Test
    @DisplayName("primary key tranId is @Id String mapped to char(16) column tran_id")
    void primaryKey() throws NoSuchFieldException {
        assertThat(field("tranId").isAnnotationPresent(Id.class)).isTrue();
        assertThat(field("tranId").getType()).isEqualTo(String.class);
        Column column = field("tranId").getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("tran_id");
        assertThat(column.length()).isEqualTo(16);
    }

    @Test
    @DisplayName("tranTypeCd is a raw String on column tran_type_cd char(2) with NO attribute converter (open PIC X(2))")
    void tranTypeCdIsRawCharStringWithoutConverter() throws NoSuchFieldException {
        Field f = field("tranTypeCd");
        assertThat(f.getType())
                .as("tran_type_cd must be a raw String for COBOL PIC X(2) write-through parity")
                .isEqualTo(String.class);
        assertThat(f.isAnnotationPresent(Convert.class))
                .as("tran_type_cd must NOT use an attribute converter (open PIC X(2), not a closed enum)")
                .isFalse();
        assertThat(f.getAnnotation(Column.class).name()).isEqualTo("tran_type_cd");
        assertThat(f.getAnnotation(Column.class).length()).isEqualTo(2);
    }

    @Test
    @DisplayName("tranAmt is BigDecimal NUMERIC(11,2)")
    void tranAmtIsBigDecimal() throws NoSuchFieldException {
        Field f = field("tranAmt");
        assertThat(f.getType()).isEqualTo(BigDecimal.class);
        Column column = f.getAnnotation(Column.class);
        assertThat(column.precision()).isEqualTo(11);
        assertThat(column.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("origTs and procTs are 26-char String text timestamps (NOT temporal types)")
    void textTimestamps() throws NoSuchFieldException {
        for (String name : new String[]{"origTs", "procTs"}) {
            Field f = field(name);
            assertThat(f.getType()).as("%s must be String text timestamp", name).isEqualTo(String.class);
            assertThat(f.getAnnotation(Column.class).length()).as("%s length", name).isEqualTo(26);
        }
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (tranId only)")
    void equalsAndHashCodeOverIdentity() {
        Transaction a = new Transaction();
        a.setTranId("0000000000000001");
        Transaction b = new Transaction();
        b.setTranId("0000000000000001");
        b.setTranAmt(new BigDecimal("10.00"));
        Transaction other = new Transaction();
        other.setTranId("0000000000000002");

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-transaction");
        assertThat(a.toString()).isNotNull();
    }
}
