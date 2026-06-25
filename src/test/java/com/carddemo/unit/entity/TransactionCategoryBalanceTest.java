package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
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
 * Unit tests for the {@link TransactionCategoryBalance} entity (COBOL CVTRA01Y
 * TRAN-CAT-BAL-RECORD @ 27d6c6f). In-memory POJO checks only — annotations are
 * validated structurally via reflection, not against a live schema.
 */
@DisplayName("TransactionCategoryBalance entity - CVTRA01Y: @EmbeddedId + NUMERIC(11,2) balance")
class TransactionCategoryBalanceTest {

    private static Field field(String name) throws NoSuchFieldException {
        return TransactionCategoryBalance.class.getDeclaredField(name);
    }

    @Test
    @DisplayName("@Entity present and mapped to table transaction_category_balance")
    void entityAndTable() {
        assertThat(TransactionCategoryBalance.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(TransactionCategoryBalance.class.getAnnotation(Table.class).name())
                .isEqualTo("transaction_category_balance");
        assertThat(TransactionCategoryBalance.class)
                .isAssignableTo(Serializable.class);
    }

    @Test
    @DisplayName("composite key 'id' is @EmbeddedId of type TransactionCategoryBalanceId")
    void embeddedId() throws NoSuchFieldException {
        Field id = field("id");
        assertThat(id.isAnnotationPresent(EmbeddedId.class)).isTrue();
        assertThat(id.getType()).isEqualTo(TransactionCategoryBalanceId.class);
    }

    @Test
    @DisplayName("tranCatBal is BigDecimal mapped to NUMERIC(11,2) column tran_cat_bal")
    void balanceColumn() throws NoSuchFieldException {
        Field balance = field("tranCatBal");
        assertThat(balance.getType()).isEqualTo(BigDecimal.class);
        Column column = balance.getAnnotation(Column.class);
        assertThat(column).as("@Column on tranCatBal").isNotNull();
        assertThat(column.name()).isEqualTo("tran_cat_bal");
        assertThat(column.precision()).isEqualTo(11);
        assertThat(column.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("no field is double/float - balance is BigDecimal (AAP 0.6.1)")
    void noFloatingPointFields() {
        for (Field f : TransactionCategoryBalance.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            assertThat(f.getType())
                    .as("field '%s' must not be floating-point (decimal exactness, AAP 0.6.1)", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }
}
