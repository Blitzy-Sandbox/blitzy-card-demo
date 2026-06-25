package com.carddemo.unit.entity;

import com.carddemo.entity.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Customer} entity (COBOL CUSTREC/CVCUS01Y CUSTOMER-RECORD, RECLN 500 @ 27d6c6f).
 * Key parity: ssn is a String CHAR(9) (NOT numeric) so leading zeros are preserved, e.g. fixture value
 * "020973888" from app/data/ASCII/custdata.txt.
 */
@DisplayName("Customer entity - CUSTREC/CVCUS01Y (500B): @Version, ssn String CHAR(9) preserves leading zeros")
class CustomerTest {

    private static Field field(String name) throws NoSuchFieldException {
        return Customer.class.getDeclaredField(name);
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
        assertNoFloatingPointFields(Customer.class);
    }

    @Test
    @DisplayName("has @Version optimistic-lock field of type Long")
    void versionPresent() throws NoSuchFieldException {
        assertThat(hasVersionAnnotation(Customer.class)).as("Customer must carry @Version").isTrue();
        assertThat(field("version").getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("primary key custId is @Id Long mapped to column cust_id")
    void primaryKey() throws NoSuchFieldException {
        assertThat(field("custId").isAnnotationPresent(Id.class)).isTrue();
        assertThat(field("custId").getType()).isEqualTo(Long.class);
        assertThat(field("custId").getAnnotation(Column.class).name()).isEqualTo("cust_id");
    }

    @Test
    @DisplayName("ssn is a String (not numeric) so leading zeros survive - column ssn char(9)")
    void ssnIsStringPreservingLeadingZeros() throws NoSuchFieldException {
        assertThat(field("ssn").getType()).as("ssn must be String, never numeric").isEqualTo(String.class);
        assertThat(field("ssn").getAnnotation(Column.class).name()).isEqualTo("ssn");
        Customer c = new Customer();
        c.setSsn("020973888");
        assertThat(c.getSsn()).as("leading zero preserved by String").isEqualTo("020973888");
    }

    @Test
    @DisplayName("ficoCreditScore is Integer and names are String")
    void selectedFieldTypes() throws NoSuchFieldException {
        assertThat(field("ficoCreditScore").getType()).isEqualTo(Integer.class);
        assertThat(field("firstName").getType()).isEqualTo(String.class);
        assertThat(field("lastName").getType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (custId only); non-id fields are ignored")
    void equalsAndHashCodeOverIdentity() {
        Customer a = new Customer();
        a.setCustId(1L);
        Customer b = new Customer();
        b.setCustId(1L);
        b.setFirstName("Immanuel");
        Customer other = new Customer();
        other.setCustId(2L);

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-customer");
        assertThat(a.toString()).isNotNull();
    }
}
