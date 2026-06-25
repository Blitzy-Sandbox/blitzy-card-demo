package com.carddemo.unit.entity;

import com.carddemo.entity.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Account} entity (COBOL CVACT01Y ACCOUNT-RECORD, RECLN 300 @ 27d6c6f).
 * In-memory POJO checks only — annotations validated structurally via reflection, NOT against a live schema.
 */
@DisplayName("Account entity - CVACT01Y (300B): @Version, NUMERIC(12,2) money, preserved 'expiraion_date' misspelling")
class AccountTest {

    private static Field field(String name) throws NoSuchFieldException {
        return Account.class.getDeclaredField(name);
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
    @DisplayName("no field is double/float - money is BigDecimal (AAP 0.6.1)")
    void noFloatingPointFields() {
        assertNoFloatingPointFields(Account.class);
    }

    @Test
    @DisplayName("has @Version optimistic-lock field 'version' of type Long")
    void versionPresent() throws NoSuchFieldException {
        assertThat(hasVersionAnnotation(Account.class)).as("Account must carry @Version").isTrue();
        assertThat(field("version").isAnnotationPresent(Version.class)).isTrue();
        assertThat(field("version").getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("primary key acctId is @Id mapped to column acct_id")
    void primaryKey() throws NoSuchFieldException {
        assertThat(field("acctId").isAnnotationPresent(Id.class)).isTrue();
        assertThat(field("acctId").getAnnotation(Column.class).name()).isEqualTo("acct_id");
        assertThat(field("acctId").getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("expirationDate column name PRESERVES the legacy misspelling 'expiraion_date'")
    void preservedColumnMisspelling() throws NoSuchFieldException {
        Column column = field("expirationDate").getAnnotation(Column.class);
        assertThat(column).as("@Column on expirationDate").isNotNull();
        assertThat(column.name()).isEqualTo("expiraion_date");
    }

    @Test
    @DisplayName("all 5 monetary fields are BigDecimal NUMERIC(12,2)")
    void monetaryFieldsAreBigDecimal() throws NoSuchFieldException {
        for (String name : List.of("currBal", "creditLimit", "cashCreditLimit", "currCycCredit", "currCycDebit")) {
            Field f = field(name);
            assertThat(f.getType()).as("%s type", name).isEqualTo(BigDecimal.class);
            Column column = f.getAnnotation(Column.class);
            assertThat(column).as("%s @Column", name).isNotNull();
            assertThat(column.precision()).as("%s precision", name).isEqualTo(12);
            assertThat(column.scale()).as("%s scale", name).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (acctId only); non-id fields are ignored")
    void equalsAndHashCodeOverIdentity() {
        Account a = new Account();
        a.setAcctId(100000001L);
        Account b = new Account();
        b.setAcctId(100000001L);
        b.setCurrBal(new BigDecimal("500.00"));
        Account other = new Account();
        other.setAcctId(999999999L);

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-an-account");
        assertThat(a.toString()).isNotNull();
    }
}
