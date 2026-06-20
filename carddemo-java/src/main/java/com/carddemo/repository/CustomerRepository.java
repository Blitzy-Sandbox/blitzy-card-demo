package com.carddemo.repository;

import com.carddemo.model.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Customer entity (re-platforms the VSAM
 * CUSTDAT KSDS). Provides keyed read of customers by customer id via inherited
 * JpaRepository operations.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
