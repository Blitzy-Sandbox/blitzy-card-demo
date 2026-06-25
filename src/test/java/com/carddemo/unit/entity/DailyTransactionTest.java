package com.carddemo.unit.entity;

import com.carddemo.entity.DailyTransaction;
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
 * Unit tests for the {@link DailyTransaction} staging entity (COBOL CVTRA06Y DALYTRAN-RECORD, RECLN 350 @ 27d6c6f).
 * Key parity contrast with {@link com.carddemo.entity.Transaction}: tranTypeCd is a RAW String with NO @Convert
 * (the staging row is unparsed/unvalidated until the posting pipeline runs).
 */
@DisplayName("DailyTransaction entity - CVTRA06Y (350B staging): RAW String tranTypeCd (NO converter), NO @Version")
class DailyTransactionTest {

    private static Field field(String name) throws NoSuchFieldException {
        return DailyTransaction.class.getDeclaredField(name);
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
        assertNoFloatingPointFields(DailyTransaction.class);
    }

    @Test
    @DisplayName("has NO @Version field")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(DailyTransaction.class)).as("DailyTransaction must NOT carry @Version").isFalse();
    }

    @Test
    @DisplayName("primary key tranId is @Id String mapped to char(16) column tran_id")
    void primaryKey() throws NoSuchFieldException {
        assertThat(field("tranId").isAnnotationPresent(Id.class)).isTrue();
        assertThat(field("tranId").getType()).isEqualTo(String.class);
        assertThat(field("tranId").getAnnotation(Column.class).name()).isEqualTo("tran_id");
    }

    @Test
    @DisplayName("tranTypeCd is a RAW String with NO @Convert (staging entity) - contrast with Transaction")
    void tranTypeCdIsRawStringWithoutConverter() throws NoSuchFieldException {
        Field f = field("tranTypeCd");
        assertThat(f.getType()).as("staging tranTypeCd must be raw String").isEqualTo(String.class);
        assertThat(f.isAnnotationPresent(Convert.class)).as("staging tranTypeCd must NOT use @Convert").isFalse();
        assertThat(f.getAnnotation(Column.class).name()).isEqualTo("tran_type_cd");
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
    @DisplayName("equals/hashCode are identity-based (tranId only)")
    void equalsAndHashCodeOverIdentity() {
        DailyTransaction a = new DailyTransaction();
        a.setTranId("0000000000000001");
        DailyTransaction b = new DailyTransaction();
        b.setTranId("0000000000000001");
        b.setTranAmt(new BigDecimal("10.00"));
        DailyTransaction other = new DailyTransaction();
        other.setTranId("0000000000000002");

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-daily-transaction");
        assertThat(a.toString()).isNotNull();
    }
}
