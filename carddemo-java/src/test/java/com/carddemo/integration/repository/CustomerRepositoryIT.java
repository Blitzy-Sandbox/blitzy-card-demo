package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link CustomerRepository} against a real PostgreSQL 16 Testcontainer.
 * Verifies the VSAM CUSTDAT KSDS re-platforms to PostgreSQL with keyed reads and the full
 * seeded customer set intact.
 */
class CustomerRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void seedDataLoadsAllCustomers() {
        assertThat(customerRepository.count()).isEqualTo(50L);
        assertThat(customerRepository.findAll()).hasSize(50);
    }

    @Test
    void findByIdReturnsSeededCustomer() {
        List<Customer> all = customerRepository.findAll();
        assertThat(all).isNotEmpty();
        Customer sample = all.get(0);
        Long custId = sample.getCustId();

        Optional<Customer> found = customerRepository.findById(custId);

        assertThat(found).isPresent();
        assertThat(found.get().getCustId()).isEqualTo(custId);
        assertThat(found.get().getCustFirstName()).isNotNull();
        assertThat(found.get().getCustLastName()).isNotNull();
        assertThat(found.get().getCustSsn()).isNotNull();
    }

    @Test
    void findByIdReturnsEmptyForUnknownCustomer() {
        long absentId = customerRepository.findAll().stream()
                .mapToLong(Customer::getCustId)
                .max()
                .orElse(0L) + 1L;

        assertThat(customerRepository.findById(absentId)).isEmpty();
    }

    @Test
    void everyCustomerHasIdentityFields() {
        for (Customer customer : customerRepository.findAll()) {
            assertThat(customer.getCustId()).isNotNull();
            assertThat(customer.getCustFirstName()).isNotNull();
            assertThat(customer.getCustLastName()).isNotNull();
        }
    }
}
