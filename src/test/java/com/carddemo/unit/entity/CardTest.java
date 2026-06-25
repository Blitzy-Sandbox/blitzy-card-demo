package com.carddemo.unit.entity;

import com.carddemo.entity.Card;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Card} entity (COBOL CVACT02Y CARD-RECORD, RECLN 150 @ 27d6c6f).
 */
@DisplayName("Card entity - CVACT02Y (150B): CHAR(16) PK, @Version, preserved 'expiraion_date' misspelling")
class CardTest {

    private static final String SAMPLE_CARD = "4111111111111111";

    private static Field field(String name) throws NoSuchFieldException {
        return Card.class.getDeclaredField(name);
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
        assertNoFloatingPointFields(Card.class);
    }

    @Test
    @DisplayName("has @Version optimistic-lock field of type Long")
    void versionPresent() throws NoSuchFieldException {
        assertThat(hasVersionAnnotation(Card.class)).as("Card must carry @Version").isTrue();
        assertThat(field("version").getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("primary key cardNum is @Id String mapped to char(16) column card_num")
    void primaryKey() throws NoSuchFieldException {
        assertThat(field("cardNum").isAnnotationPresent(Id.class)).isTrue();
        assertThat(field("cardNum").getType()).isEqualTo(String.class);
        Column column = field("cardNum").getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("card_num");
        assertThat(column.length()).isEqualTo(16);
    }

    @Test
    @DisplayName("expirationDate column name PRESERVES the legacy misspelling 'expiraion_date'")
    void preservedColumnMisspelling() throws NoSuchFieldException {
        assertThat(field("expirationDate").getAnnotation(Column.class).name()).isEqualTo("expiraion_date");
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (cardNum only); non-id fields are ignored")
    void equalsAndHashCodeOverIdentity() {
        Card a = new Card();
        a.setCardNum(SAMPLE_CARD);
        Card b = new Card();
        b.setCardNum(SAMPLE_CARD);
        b.setEmbossedName("JANE DOE");
        Card other = new Card();
        other.setCardNum("4222222222222222");

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-card");
        assertThat(a.toString()).isNotNull();
    }
}
