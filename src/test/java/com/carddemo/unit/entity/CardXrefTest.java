package com.carddemo.unit.entity;

import com.carddemo.entity.CardXref;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link CardXref} entity (COBOL CVACT03Y CARD-XREF-RECORD, RECLN 50 @ 27d6c6f).
 * This entity has NO @Version (no optimistic locking) - asserted explicitly.
 */
@DisplayName("CardXref entity - CVACT03Y (50B): CHAR(16) PK, NO @Version")
class CardXrefTest {

    private static Field field(String name) throws NoSuchFieldException {
        return CardXref.class.getDeclaredField(name);
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
        assertNoFloatingPointFields(CardXref.class);
    }

    @Test
    @DisplayName("has NO @Version field (no optimistic locking for the xref)")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(CardXref.class)).as("CardXref must NOT carry @Version").isFalse();
    }

    @Test
    @DisplayName("primary key xrefCardNum is @Id String mapped to char(16) column xref_card_num")
    void primaryKey() throws NoSuchFieldException {
        assertThat(field("xrefCardNum").isAnnotationPresent(Id.class)).isTrue();
        assertThat(field("xrefCardNum").getType()).isEqualTo(String.class);
        Column column = field("xrefCardNum").getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("xref_card_num");
        assertThat(column.length()).isEqualTo(16);
    }

    @Test
    @DisplayName("foreign-key fields xrefCustId and xrefAcctId are Long")
    void foreignKeyFieldTypes() throws NoSuchFieldException {
        assertThat(field("xrefCustId").getType()).isEqualTo(Long.class);
        assertThat(field("xrefAcctId").getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (xrefCardNum only); non-id fields are ignored")
    void equalsAndHashCodeOverIdentity() {
        CardXref a = new CardXref();
        a.setXrefCardNum("4111111111111111");
        CardXref b = new CardXref();
        b.setXrefCardNum("4111111111111111");
        b.setXrefCustId(42L);
        CardXref other = new CardXref();
        other.setXrefCardNum("4222222222222222");

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-xref");
        assertThat(a.toString()).isNotNull();
    }
}
