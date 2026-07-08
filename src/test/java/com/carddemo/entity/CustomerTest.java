package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Customer} entity (COBOL {@code CUSTOMER-RECORD} /
 * copybook {@code CVCUS01Y}): accessor round-trips, numeric-id wrapper types, and
 * {@link LocalDate} DOB.
 *
 * <p>{@code Customer} is the migrated Java representation of the fixed 500-byte
 * {@code CUSTOMER-RECORD} (source commit {@code 27d6c6f}, read-only reference — the
 * legacy COBOL is not copied into the target). The record carries no money fields
 * and is never mutated through a read-then-rewrite path, so the entity has neither a
 * {@code BigDecimal} column nor an optimistic-lock {@code @Version}. These tests
 * therefore concentrate on three concerns:</p>
 * <ul>
 *   <li>every accessor round-trips its value across the wide record — what is set is
 *       exactly what the corresponding getter returns;</li>
 *   <li>the three numeric identifiers map to <em>wrapper</em> types — {@code custId}
 *       and {@code custSsn} to {@link Long}, {@code custFicoCreditScore} to
 *       {@link Integer} — never to primitives, so an unset value is representable as
 *       {@code null} rather than a misleading {@code 0}; and</li>
 *   <li>the date-of-birth field is a first-class {@link LocalDate}.</li>
 * </ul>
 *
 * <p>This is a plain-POJO test: no Spring context, no persistence, no Testcontainers,
 * and no mocks. Only wrapper, {@link String}, and {@link LocalDate} types are
 * exercised (no {@code float}/{@code double}), matching the decimal-fidelity
 * constraints of the migration. All sample values are obviously fabricated,
 * non-real PII.</p>
 */
class CustomerTest {

    /**
     * The three numeric fields must round-trip as wrapper types: {@code CUST-ID} and
     * {@code CUST-SSN} ({@code PIC 9(09)}) as {@link Long}, and
     * {@code CUST-FICO-CREDIT-SCORE} ({@code PIC 9(03)}) as {@link Integer}. A
     * nine-digit identifier fits comfortably within {@code long}/{@code int} range,
     * but the entity models these as wrappers so that "no value" is representable as
     * {@code null}; the assertions use {@link Long#valueOf(long)} /
     * {@link Integer#valueOf(int)} to pin the boxed type explicitly.
     */
    @Test
    @DisplayName("numeric ids custId/custSsn round-trip as Long and ficoScore as Integer")
    void naturalKeyAndNumericIdsRoundTrip() {
        Customer customer = new Customer();

        customer.setCustId(123456789L);
        customer.setCustSsn(987654321L);
        customer.setCustFicoCreditScore(750);

        assertThat(customer.getCustId()).isEqualTo(Long.valueOf(123456789L));
        assertThat(customer.getCustSsn()).isEqualTo(Long.valueOf(987654321L));
        assertThat(customer.getCustFicoCreditScore()).isEqualTo(Integer.valueOf(750));
    }

    /**
     * The three name fields ({@code X(25)} each), the three address lines
     * ({@code X(50)} each), and the state / country / ZIP codes ({@code X(02)},
     * {@code X(03)}, {@code X(10)}) must each round-trip unchanged through their
     * accessors.
     */
    @Test
    @DisplayName("name and address string fields round-trip through their accessors")
    void nameAndAddressFieldsRoundTrip() {
        Customer customer = new Customer();

        customer.setCustFirstName("JANE");
        customer.setCustMiddleName("QUINCY");
        customer.setCustLastName("DOE");
        customer.setCustAddrLine1("100 EXAMPLE STREET");
        customer.setCustAddrLine2("SUITE 200");
        customer.setCustAddrLine3("BUILDING C");
        customer.setCustAddrStateCd("TX");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("73301-0001");

        assertThat(customer.getCustFirstName()).isEqualTo("JANE");
        assertThat(customer.getCustMiddleName()).isEqualTo("QUINCY");
        assertThat(customer.getCustLastName()).isEqualTo("DOE");
        assertThat(customer.getCustAddrLine1()).isEqualTo("100 EXAMPLE STREET");
        assertThat(customer.getCustAddrLine2()).isEqualTo("SUITE 200");
        assertThat(customer.getCustAddrLine3()).isEqualTo("BUILDING C");
        assertThat(customer.getCustAddrStateCd()).isEqualTo("TX");
        assertThat(customer.getCustAddrCountryCd()).isEqualTo("USA");
        assertThat(customer.getCustAddrZip()).isEqualTo("73301-0001");
    }

    /**
     * The two phone numbers ({@code X(15)}), the government-issued id ({@code X(20)}),
     * the EFT account id ({@code X(10)}), and the single-character
     * primary-card-holder indicator ({@code X(01)}) must each round-trip unchanged.
     */
    @Test
    @DisplayName("phone, government id, EFT account, and card-holder indicator round-trip")
    void phoneAndMiscStringFieldsRoundTrip() {
        Customer customer = new Customer();

        customer.setCustPhoneNum1("(555) 123-4567");
        customer.setCustPhoneNum2("(555) 987-6543");
        customer.setCustGovtIssuedId("GOVT-ID-0000000001");
        customer.setCustEftAccountId("EFT0000001");
        customer.setCustPriCardHolderInd("Y");

        assertThat(customer.getCustPhoneNum1()).isEqualTo("(555) 123-4567");
        assertThat(customer.getCustPhoneNum2()).isEqualTo("(555) 987-6543");
        assertThat(customer.getCustGovtIssuedId()).isEqualTo("GOVT-ID-0000000001");
        assertThat(customer.getCustEftAccountId()).isEqualTo("EFT0000001");
        assertThat(customer.getCustPriCardHolderInd()).isEqualTo("Y");
    }

    /**
     * The COBOL {@code CUST-DOB-YYYY-MM-DD} ({@code PIC X(10)}) date-of-birth string
     * is modeled as a {@link LocalDate}; setting a date must return an equal date.
     */
    @Test
    @DisplayName("date-of-birth field round-trips as a LocalDate")
    void dobRoundTrip() {
        Customer customer = new Customer();

        customer.setCustDobYyyyMmDd(LocalDate.of(1985, 3, 20));

        assertThat(customer.getCustDobYyyyMmDd()).isEqualTo(LocalDate.of(1985, 3, 20));
    }

    /**
     * A freshly constructed {@code Customer} must report {@code null} for its object
     * fields, confirming the numeric identifiers are wrapper types (no primitive
     * {@code 0} defaults) and that no field is eagerly initialized.
     */
    @Test
    @DisplayName("a new Customer has null object fields (wrapper ids default to null, not 0)")
    void newInstanceDefaultsAreNull() {
        Customer customer = new Customer();

        assertThat(customer.getCustId()).isNull();
        assertThat(customer.getCustSsn()).isNull();
        assertThat(customer.getCustFicoCreditScore()).isNull();
        assertThat(customer.getCustFirstName()).isNull();
        assertThat(customer.getCustLastName()).isNull();
        assertThat(customer.getCustDobYyyyMmDd()).isNull();
        assertThat(customer.getCustPriCardHolderInd()).isNull();
    }
}
