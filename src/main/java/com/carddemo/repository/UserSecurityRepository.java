package com.carddemo.repository;

import com.carddemo.entity.UserSecurity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link UserSecurity} aggregate.
 *
 * <p>This interface is the migration replacement for file-based keyed access to
 * the legacy {@code USRSEC} VSAM KSDS dataset (record layout from copybook
 * {@code CSUSR01Y}, 80-byte {@code SEC-USER-DATA} record, base key
 * {@code SEC-USR-ID PIC X(08)}; frozen COBOL reference at source commit SHA
 * {@code 27d6c6f}). The file-based authentication model is preserved intact by
 * the migration — RACF is <em>not</em> introduced — so this repository remains
 * the single point of persistence for the security-user store.</p>
 *
 * <h2>Backing programs and access patterns</h2>
 * <p>Every {@code USRSEC} access performed by the legacy programs is a keyed
 * operation on the primary key {@code SEC-USR-ID}, and each maps onto a method
 * already inherited from {@link JpaRepository} /
 * {@link org.springframework.data.repository.PagingAndSortingRepository} /
 * {@link org.springframework.data.repository.CrudRepository}. No custom query
 * methods are therefore declared: the inherited surface covers 100% of the
 * required access, and adding redundant derived queries would constitute
 * unrequested scope expansion.</p>
 * <ul>
 *   <li><strong>Sign-on ({@code COSGN00C}, transaction {@code CC00}):</strong>
 *       the {@code READ-USER-SEC-FILE} paragraph issues
 *       {@code EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)} — a
 *       primary-key read — which maps to the inherited
 *       {@code findById(String)}. Password
 *       verification against the stored BCrypt hash is performed by the
 *       security layer ({@code SignonService} / {@code SecurityConfig}), never
 *       by this repository.</li>
 *   <li><strong>List users ({@code COUSR00C}, transaction {@code CU00}):</strong>
 *       the legacy browse walks {@code USRSEC} in {@code SEC-USR-ID} order,
 *       ten records per screen ({@code USER-REC OCCURS 10 TIMES}). This maps to
 *       the inherited {@code findAll(Pageable)} declared on
 *       {@link org.springframework.data.repository.PagingAndSortingRepository};
 *       the ten-rows-per-page window is supplied by the calling service via
 *       {@code PageRequest.of(page, 10)}.</li>
 *   <li><strong>Add / update / delete user ({@code COUSR01C} /
 *       {@code COUSR02C} / {@code COUSR03C}, transactions {@code CU01}–
 *       {@code CU03}):</strong> single-key create, read-modify-write, and
 *       delete operations map to the inherited {@code save}, {@code findById},
 *       {@code existsById}, and {@code deleteById}.</li>
 * </ul>
 *
 * <h2>Identifier type</h2>
 * <p>The aggregate identifier is {@code String}: {@link UserSecurity} declares
 * its {@code @Id} field {@code secUsrId} as a {@code String}, migrated from the
 * COBOL natural key {@code SEC-USR-ID PIC X(08)} (a {@code VARCHAR(8)} primary
 * key). User identifiers are administrator-assigned exactly as in the legacy
 * {@code USRSEC} file, so no surrogate/generated key is used.</p>
 *
 * <h2>Schema ownership</h2>
 * <p>This repository is read/write over the Flyway-managed {@code user_security}
 * table (named {@code user_security} rather than the reserved word
 * {@code user}). Hibernate runs with {@code ddl-auto: validate}; the schema is
 * owned by the Flyway migrations, and no DDL originates here.</p>
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {
    // Intentionally no declarations. All USRSEC access (COSGN00C sign-on read,
    // COUSR00C paged list, COUSR01C/02C/03C CRUD) is satisfied by the methods
    // inherited from JpaRepository, PagingAndSortingRepository, and
    // CrudRepository — findById, findAll(Pageable), save, existsById, and
    // deleteById. No custom or derived query methods are required.
}
