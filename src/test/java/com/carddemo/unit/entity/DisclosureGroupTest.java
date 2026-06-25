package com.carddemo.unit.entity;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link DisclosureGroup} entity (COBOL CVTRA02Y
 * DIS-GROUP-RECORD, RECLN 50 @ 27d6c6f). In-memory POJO checks only —
 * annotations are validated structurally via reflection, not against a live
 * schema.
 */
@DisplayName("DisclosureGroup entity - CVTRA02Y (50B): @EmbeddedId + NUMERIC(6,2) interest rate")
class DisclosureGroupTest {

    private static Field field(String name) throws NoSuchFieldException {
        return DisclosureGroup.class.getDeclaredField(name);
    }

    @Test
    @DisplayName("@Entity present and mapped to table disclosure_group")
    void entityAndTable() {
        assertThat(DisclosureGroup.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(DisclosureGroup.class.getAnnotation(Table.class).name())
                .isEqualTo("disclosure_group");
        assertThat(DisclosureGroup.class).isAssignableTo(Serializable.class);
    }

    @Test
    @DisplayName("composite key 'id' is @EmbeddedId of type DisclosureGroupId")
    void embeddedId() throws NoSuchFieldException {
        Field id = field("id");
        assertThat(id.isAnnotationPresent(EmbeddedId.class)).isTrue();
        assertThat(id.getType()).isEqualTo(DisclosureGroupId.class);
    }

    @Test
    @DisplayName("disIntRate is BigDecimal mapped to NUMERIC(6,2) column dis_int_rate")
    void interestRateColumn() throws NoSuchFieldException {
        Field rate = field("disIntRate");
        assertThat(rate.getType()).isEqualTo(BigDecimal.class);
        Column column = rate.getAnnotation(Column.class);
        assertThat(column).as("@Column on disIntRate").isNotNull();
        assertThat(column.name()).isEqualTo("dis_int_rate");
        assertThat(column.precision()).isEqualTo(6);
        assertThat(column.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("no field is double/float - rate is BigDecimal (AAP 0.6.1)")
    void noFloatingPointFields() {
        for (Field f : DisclosureGroup.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            assertThat(f.getType())
                    .as("field '%s' must not be floating-point (decimal exactness, AAP 0.6.1)", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }
}
