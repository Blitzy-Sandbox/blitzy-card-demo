package com.carddemo.unit.entity;

import com.carddemo.entity.TransactionTypeConverter;
import com.carddemo.enums.TransactionTypeCode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TransactionTypeConverter} — the JPA AttributeConverter mapping the typed
 * {@link TransactionTypeCode} enum to/from the 2-character DB column (COBOL TRAN-TYPE PIC X(02),
 * copybook CVTRA03Y / fixture app/data/ASCII/trantype.txt @ 27d6c6f). Pure in-memory POJO logic:
 * no Spring context, no JPA EntityManager, no database.
 */
@DisplayName("TransactionTypeConverter - enum <-> 2-char column mapping")
class TransactionTypeConverterTest {

    private final TransactionTypeConverter converter = new TransactionTypeConverter();

    @Test
    @DisplayName("@Converter(autoApply=false) and implements AttributeConverter<TransactionTypeCode,String>")
    void converterStructure() {
        Converter annotation = TransactionTypeConverter.class.getAnnotation(Converter.class);
        assertThat(annotation).as("@Converter must be present").isNotNull();
        assertThat(annotation.autoApply()).as("autoApply must be false").isFalse();
        assertThat(AttributeConverter.class.isAssignableFrom(TransactionTypeConverter.class)).isTrue();
    }

    @Test
    @DisplayName("convertToDatabaseColumn(null) returns null (no NPE)")
    void toDatabaseNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("convertToDatabaseColumn maps each enum to its exact 2-char code")
    void toDatabaseValues() {
        assertThat(converter.convertToDatabaseColumn(TransactionTypeCode.PURCHASE)).isEqualTo("01");
        assertThat(converter.convertToDatabaseColumn(TransactionTypeCode.PAYMENT)).isEqualTo("02");
        assertThat(converter.convertToDatabaseColumn(TransactionTypeCode.ADJUSTMENT)).isEqualTo("07");
    }

    @Test
    @DisplayName("convertToEntityAttribute(null) returns null - null-guard precedes fromCode (no NPE)")
    void toEntityNull() {
        assertThatCode(() -> assertThat(converter.convertToEntityAttribute(null)).isNull())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("convertToEntityAttribute blank/whitespace returns null (no NPE, no IllegalArgumentException)")
    void toEntityBlank() {
        assertThatCode(() -> {
            assertThat(converter.convertToEntityAttribute("")).isNull();
            assertThat(converter.convertToEntityAttribute("  ")).isNull();
            assertThat(converter.convertToEntityAttribute("\t")).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("convertToEntityAttribute maps valid 2-char code to its enum constant")
    void toEntityValues() {
        assertThat(converter.convertToEntityAttribute("01")).isEqualTo(TransactionTypeCode.PURCHASE);
        assertThat(converter.convertToEntityAttribute("07")).isEqualTo(TransactionTypeCode.ADJUSTMENT);
    }

    @Test
    @DisplayName("convertToEntityAttribute unknown code throws IllegalArgumentException")
    void toEntityUnknown() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("99"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("round-trip enum -> column -> enum is identity for every constant")
    void roundTrip() {
        for (TransactionTypeCode code : TransactionTypeCode.values()) {
            String column = converter.convertToDatabaseColumn(code);
            assertThat(converter.convertToEntityAttribute(column)).isEqualTo(code);
        }
    }
}
