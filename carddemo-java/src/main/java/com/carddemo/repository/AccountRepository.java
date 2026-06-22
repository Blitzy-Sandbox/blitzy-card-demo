package com.carddemo.repository;

import com.carddemo.model.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Account entity (re-platforms the VSAM
 * ACCTDAT KSDS). Provides keyed read and update of accounts by account id via
 * inherited JpaRepository operations; optimistic concurrency is enforced by the
 * entity's {@code @Version} field. The forced aggregate-version increment that
 * keeps the ACCTDAT+CUSTDAT update protected against stale customer-only
 * submits is applied in {@code AccountUpdateService} via the JPA
 * {@code OPTIMISTIC_FORCE_INCREMENT} lock so the post-commit version is reflected
 * synchronously in the echoed response (AAP 0.8.4).
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}
