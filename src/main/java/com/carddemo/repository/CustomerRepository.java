package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Customer} aggregate.
 *
 * <p>This interface is the modern replacement for the VSAM KSDS keyed access to the
 * customer master file described by COBOL copybook {@code app/cpy/CVCUS01Y.cpy}
 * ({@code CUSTOMER-RECORD}, fixed record length 500 bytes, source SHA {@code 27d6c6f}).
 * The legacy customer KSDS was read by primary key {@code CUST-ID PIC 9(09)}; that base
 * key maps to the {@link Customer#getCustId() custId} property, whose Java type is
 * {@link Long} (COBOL {@code 9(09)} &rarr; {@code BIGINT}). The repository's identifier
 * type parameter therefore is {@link Long}.</p>
 *
 * <p>Access pattern: customer records are resolved by identifier during account and card
 * navigation and during statement generation. The card&rarr;customer relationship is
 * resolved through the cross-reference aggregate ({@code CardXref}, copybook
 * {@code CVACT03Y}) which yields the customer identifier; the customer row is then fetched
 * here by primary key. Consequently every read path is satisfied by the inherited
 * {@link JpaRepository#findById(Object) findById(Long)} operation, and persistence by the
 * inherited {@link JpaRepository#save(Object) save(Customer)} operation.</p>
 *
 * <p>Scope guard: no derived queries, {@code @Query} methods, or entity relationships are
 * declared here. Card-to-customer joins are intentionally resolved via the cross-reference
 * repository rather than on this interface, keeping the migrated surface area equivalent to
 * the legacy keyed-access contract (no feature expansion).</p>
 *
 * <p>Persistence contract: the entity maps to the Flyway-managed {@code customer} table with
 * Hibernate {@code ddl-auto: validate}; this repository issues no schema DDL. Standard CRUD,
 * paging, and sorting behavior is inherited from {@link JpaRepository} and requires no
 * additional declarations.</p>
 *
 * @see Customer
 * @see JpaRepository
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    // Intentionally empty: all customer access is by primary key (CUST-ID), fully covered
    // by the inherited JpaRepository operations (findById, save, existsById, delete, ...).
    // No custom query methods are declared (scope guard: no expansion beyond the legacy
    // VSAM KSDS keyed-access contract).
}
