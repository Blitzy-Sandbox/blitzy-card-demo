package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Customer} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVCUS01Y round-trip (numeric SSN/FICO as boxed numerics) and identity by custId per AAP {@code §0.5}.
 */
@DisplayName("Customer entity — CVCUS01Y mapping, identity by custId")
class CustomerTest {

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = new Customer();
    }

    @Test
    @DisplayName("representative fields round-trip, incl. numeric SSN and FICO score")
    void fieldsRoundTrip() {
        customer.setCustId(123456789L);
        customer.setCustFirstName("JANE");
        customer.setCustMiddleName("Q");
        customer.setCustLastName("DOE");
        customer.setCustAddrLine1("1 MAIN ST");
        customer.setCustAddrStateCd("TX");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("73301");
        customer.setCustSsn(123456789L);
        customer.setCustGovtIssuedId("TX-DL-001");
        customer.setCustDobYyyyMmDd("1990-05-15");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(720);

        assertThat(customer.getCustId()).isEqualTo(123456789L);
        assertThat(customer.getCustFirstName()).isEqualTo("JANE");
        assertThat(customer.getCustMiddleName()).isEqualTo("Q");
        assertThat(customer.getCustLastName()).isEqualTo("DOE");
        assertThat(customer.getCustAddrLine1()).isEqualTo("1 MAIN ST");
        assertThat(customer.getCustAddrStateCd()).isEqualTo("TX");
        assertThat(customer.getCustAddrCountryCd()).isEqualTo("USA");
        assertThat(customer.getCustAddrZip()).isEqualTo("73301");
        assertThat(customer.getCustSsn()).isEqualTo(123456789L);
        assertThat(customer.getCustGovtIssuedId()).isEqualTo("TX-DL-001");
        assertThat(customer.getCustDobYyyyMmDd()).isEqualTo("1990-05-15");
        assertThat(customer.getCustPriCardHolderInd()).isEqualTo("Y");
        assertThat(customer.getCustFicoCreditScore()).isEqualTo(720);
    }

    @Test
    @DisplayName("identity is by custId only; equals(null)/equals(other type) are false")
    void identityByCustId() {
        customer.setCustId(111L);
        customer.setCustLastName("DOE");
        Customer same = new Customer();
        same.setCustId(111L);
        same.setCustLastName("SMITH");
        Customer diff = new Customer();
        diff.setCustId(222L);

        assertThat(customer).isEqualTo(same);
        assertThat(customer).hasSameHashCodeAs(same);
        assertThat(customer).isNotEqualTo(diff);
        assertThat(customer.equals(null)).isFalse();
        assertThat(customer.equals("nope")).isFalse();
    }
}
