package com.carddemo.repository;

import com.carddemo.model.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Account entity (re-platforms the VSAM
 * ACCTDAT KSDS). Provides keyed read and update of accounts by account id via
 * inherited JpaRepository operations; optimistic concurrency is enforced by the
 * entity's {@code @Version} field.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}
