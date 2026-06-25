package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link TransactionCategory} entity (COBOL CVTRA04Y
 * TRAN-CAT-RECORD @ 27d6c6f). In-memory POJO checks only — annotations are
 * validated structurally via reflection, not against a live schema.
 */
@DisplayName("TransactionCategory entity - CVTRA04Y: @EmbeddedId + VARCHAR(50) description")
class TransactionCategoryTest {

    private static Field field(String name) throws NoSuchFieldException {
        return TransactionCategory.class.getDeclaredField(name);
    }

    @Test
    @DisplayName("@Entity present and mapped to table transaction_category")
    void entityAndTable() {
        assertThat(TransactionCategory.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(TransactionCategory.class.getAnnotation(Table.class).name())
                .isEqualTo("transaction_category");
        assertThat(TransactionCategory.class).isAssignableTo(Serializable.class);
    }

    @Test
    @DisplayName("composite key 'id' is @EmbeddedId of type TransactionCategoryId")
    void embeddedId() throws NoSuchFieldException {
        Field id = field("id");
        assertThat(id.isAnnotationPresent(EmbeddedId.class)).isTrue();
        assertThat(id.getType()).isEqualTo(TransactionCategoryId.class);
    }

    @Test
    @DisplayName("tranCatTypeDesc is String mapped to VARCHAR(50) column tran_cat_type_desc")
    void descriptionColumn() throws NoSuchFieldException {
        Field desc = field("tranCatTypeDesc");
        assertThat(desc.getType()).isEqualTo(String.class);
        Column column = desc.getAnnotation(Column.class);
        assertThat(column).as("@Column on tranCatTypeDesc").isNotNull();
        assertThat(column.name()).isEqualTo("tran_cat_type_desc");
        assertThat(column.length()).isEqualTo(50);
    }
}
