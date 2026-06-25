package com.carddemo.unit.entity;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
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
 * Unit tests for the {@link DisclosureGroup} entity (COBOL CVTRA02Y DIS-GROUP-RECORD @ 27d6c6f):
 * composite @EmbeddedId key + disIntRate BigDecimal NUMERIC(6,2). disIntRate feeds the InterestProcessor
 * formula (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 — its BigDecimal type and scale=2 are precision-critical.
 */
@DisplayName("DisclosureGroup entity - CVTRA02Y: @EmbeddedId + disIntRate NUMERIC(6,2), NO @Version")
class DisclosureGroupTest {

    private static Field field(String name) throws NoSuchFieldException {
        return DisclosureGroup.class.getDeclaredField(name);
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
    @DisplayName("no field is double/float - disIntRate is BigDecimal (interest formula precision)")
    void noFloatingPointFields() {
        assertNoFloatingPointFields(DisclosureGroup.class);
    }

    @Test
    @DisplayName("has NO @Version field")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(DisclosureGroup.class)).isFalse();
    }

    @Test
    @DisplayName("id is an @EmbeddedId of type DisclosureGroupId")
    void embeddedId() throws NoSuchFieldException {
        Field id = field("id");
        assertThat(id.isAnnotationPresent(EmbeddedId.class)).isTrue();
        assertThat(id.getType()).isEqualTo(DisclosureGroupId.class);
    }

    @Test
    @DisplayName("disIntRate is BigDecimal NUMERIC(6,2) - feeds the interest formula")
    void disIntRateIsBigDecimal() throws NoSuchFieldException {
        Field f = field("disIntRate");
        assertThat(f.getType()).isEqualTo(BigDecimal.class);
        Column column = f.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("dis_int_rate");
        assertThat(column.precision()).isEqualTo(6);
        assertThat(column.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (embedded id only)")
    void equalsAndHashCodeOverIdentity() {
        DisclosureGroup a = new DisclosureGroup();
        a.setId(new DisclosureGroupId("DEFAULT   ", "01", 5));
        DisclosureGroup b = new DisclosureGroup();
        b.setId(new DisclosureGroupId("DEFAULT   ", "01", 5));
        b.setDisIntRate(new BigDecimal("12.50"));
        DisclosureGroup other = new DisclosureGroup();
        other.setId(new DisclosureGroupId("OTHER     ", "01", 5));

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-disclosure-group");
        assertThat(a.toString()).isNotNull();
    }
}
