package com.carddemo.repository;

import com.carddemo.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Account} aggregate.
 *
 * <p>This interface is the modern replacement for the legacy VSAM
 * <strong>KSDS</strong> {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}, whose record
 * layout is defined by the COBOL copybook {@code app/cpy/CVACT01Y.cpy}
 * ({@code ACCOUNT-RECORD}, source commit SHA {@code 27d6c6f}). The VSAM base
 * cluster is keyed on {@code ACCT-ID}; that keyed access is now expressed as
 * primary-key access through Spring Data JPA.</p>
 *
 * <h2>COBOL access-pattern mapping</h2>
 * <p>Only two account access patterns exist in the legacy online tier, and both
 * are keyed strictly on the primary key {@code ACCT-ID}. They are served
 * entirely by the methods inherited from
 * {@link org.springframework.data.jpa.repository.JpaRepository}, so this
 * interface intentionally declares <em>no</em> custom, derived, or
 * {@code @Query} methods (scope is bounded to real COBOL access patterns):</p>
 * <ul>
 *   <li><strong>Account View</strong> (transaction {@code CAVW}, program
 *       {@code COACTVWC}) &mdash; a keyed read of a single account by
 *       {@code ACCT-ID} maps to
 *       {@link org.springframework.data.repository.CrudRepository#findById(Object)
 *       findById(Long)}.</li>
 *   <li><strong>Account Update</strong> (transaction {@code CAUP}, program
 *       {@code COACTUPC}) &mdash; the read-then-rewrite update maps to a
 *       {@code findById(Long)} followed by
 *       {@link org.springframework.data.repository.CrudRepository#save(Object)
 *       save(Account)}.</li>
 * </ul>
 *
 * <h2>Optimistic concurrency</h2>
 * <p>{@code COACTUPC} implements a "read record, verify it has not changed, then
 * rewrite" concurrency check. That behavior is preserved by the
 * {@link jakarta.persistence.Version @Version} column declared on the
 * {@link Account} <em>entity</em> (not here): a concurrent modification causes
 * the JPA provider to raise an optimistic-lock failure on {@code save}, which
 * the service layer translates into the COBOL "record changed" outcome. The
 * repository itself remains a thin, method-free persistence port.</p>
 *
 * <h2>Identifier type</h2>
 * <p>The type parameters are {@code <Account, Long>}. The COBOL key
 * {@code ACCT-ID PIC 9(11)} (an eleven-digit unsigned numeric) is mapped to a
 * Java {@link Long} / SQL {@code BIGINT} primary key on
 * {@link Account#getAcctId()}, so {@code Long} is the correct id parameter type
 * for {@code findById}, {@code getReferenceById}, {@code existsById}, and the
 * other inherited id-based operations.</p>
 *
 * <h2>Schema contract</h2>
 * <p>The application runs with {@code spring.jpa.hibernate.ddl-auto: validate};
 * this repository performs no DDL and operates over the {@code account} table
 * created by the Flyway migration {@code db/migration/V1__schema.sql}. The
 * {@link Repository @Repository} stereotype is applied for explicitness and
 * layer consistency &mdash; Spring Data JPA would auto-detect and create the
 * proxy for this interface regardless.</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}
