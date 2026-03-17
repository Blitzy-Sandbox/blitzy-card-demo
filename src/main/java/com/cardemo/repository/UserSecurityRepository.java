/*
 * UserSecurityRepository.java — Spring Data JPA Repository for User Security Records
 *
 * Migrated from COBOL source artifacts:
 *   - app/jcl/DUSRSECJ.jcl  (VSAM KSDS definition: KEYS(8,0), RECORDSIZE(80,80), commit 27d6c6f)
 *   - app/cpy/CSUSR01Y.cpy  (SEC-USER-DATA record layout, 80 bytes, commit 27d6c6f)
 *
 * This repository interface replaces all VSAM keyed access patterns for the
 * USRSEC.VSAM.KSDS dataset. The VSAM cluster is defined with:
 *   KEYS(8,0)          — 8-byte primary key at offset 0 (SEC-USR-ID)
 *   RECORDSIZE(80,80)  — fixed-length 80-byte records
 *   REUSE              — dataset reuse permitted
 *   FREESPACE(10,15)   — frequent read/write access pattern
 *   No alternate indexes
 *
 * COBOL Access Patterns Mapped:
 *   - COSGN00C.cbl: READ USRSEC KEY IS SEC-USR-ID → findBySecUsrId(String)
 *     Authentication lookup — reads user record by user ID to verify BCrypt password
 *   - COUSR00C.cbl: STARTBR/READNEXT on USRSEC → findBySecUsrIdGreaterThanEqual(String, Pageable)
 *     Paginated user list browse starting from a given user ID position
 *   - COUSR01C.cbl: WRITE USRSEC → save(UserSecurity)
 *     User creation with BCrypt password hashing (upgraded from plaintext)
 *   - COUSR02C.cbl: READ + REWRITE USRSEC → findById(String) + save(UserSecurity)
 *     User record modification
 *   - COUSR03C.cbl: READ + DELETE USRSEC → findById(String) + deleteById(String)
 *     User deletion with confirmation
 *
 * Spring Data JPA auto-generates the implementation proxy at runtime. All inherited
 * JpaRepository methods (findById, findAll, save, deleteById, count, existsById)
 * directly replace the corresponding COBOL VSAM I/O operations.
 *
 * @see com.cardemo.model.entity.UserSecurity
 */
package com.cardemo.repository;

import com.cardemo.model.entity.UserSecurity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link UserSecurity} entity CRUD operations.
 *
 * <p>Extends {@link JpaRepository} parameterized with {@code UserSecurity} (entity)
 * and {@code String} (primary key type matching the 8-byte {@code SEC-USR-ID PIC X(08)}
 * from the COBOL {@code CSUSR01Y.cpy} copybook).</p>
 *
 * <p>Provides two custom derived query methods beyond the inherited JpaRepository
 * operations:</p>
 * <ul>
 *   <li>{@link #findBySecUsrId(String)} — Authentication lookup by user ID,
 *       mapping COBOL {@code READ USRSEC KEY IS SEC-USR-ID} from COSGN00C.cbl.
 *       Returns {@link Optional} to safely handle non-existent user IDs
 *       (COBOL FILE STATUS '23' = record not found).</li>
 *   <li>{@link #findBySecUsrIdGreaterThanEqual(String, Pageable)} — Paginated
 *       browse starting from a given user ID position, mapping COBOL
 *       {@code STARTBR/READNEXT} sequential browse from COUSR00C.cbl.</li>
 * </ul>
 *
 * <p>The {@code @Repository} annotation enables Spring component scanning and
 * persistence exception translation from JPA-specific exceptions to Spring's
 * {@code DataAccessException} hierarchy.</p>
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    /**
     * Finds a user security record by user identifier.
     *
     * <p>This is the primary authentication lookup method, mapping the COBOL
     * {@code READ USRSEC KEY IS SEC-USR-ID} pattern from {@code COSGN00C.cbl}.
     * The {@code AuthenticationService} uses this method to retrieve the user
     * record and then verifies the BCrypt password hash.</p>
     *
     * <p>Since {@code secUsrId} is the primary key ({@code @Id}),
     * {@link #findById(String)} from {@code JpaRepository} provides identical
     * semantics. This explicit method exists for named semantic clarity on the
     * authentication code path, making the intent self-documenting.</p>
     *
     * <p>Returns an empty {@link Optional} when the user ID does not exist,
     * mapping the COBOL {@code FILE STATUS '23'} (record not found) condition
     * to a safe, null-free Java idiom.</p>
     *
     * @param secUsrId the user identifier to look up; up to 8 characters
     *                 matching COBOL {@code SEC-USR-ID PIC X(08)}.
     *                 Examples: {@code "ADMIN001"}, {@code "USER0001"}.
     * @return an {@link Optional} containing the {@link UserSecurity} record
     *         if found, or an empty {@link Optional} if the user ID does not
     *         exist in the database
     */
    Optional<UserSecurity> findBySecUsrId(String secUsrId);

    /**
     * Finds user security records with user ID greater than or equal to the
     * specified value, with pagination support.
     *
     * <p>Maps the COBOL {@code STARTBR/READNEXT} sequential browse pattern from
     * {@code COUSR00C.cbl}. In COBOL, the user list screen uses
     * {@code EXEC CICS STARTBR FILE('USRSEC') RIDFLD(WS-USER-ID)}
     * followed by repeated {@code READNEXT} calls to populate the screen.
     * This method provides equivalent functionality using Spring Data's
     * derived query mechanism with pagination.</p>
     *
     * <p>The {@code GreaterThanEqual} suffix in the method name instructs
     * Spring Data to generate a {@code WHERE sec_usr_id >= :secUsrId}
     * clause, combined with the {@link Pageable} parameter for
     * {@code LIMIT/OFFSET} pagination and {@code ORDER BY} sorting.</p>
     *
     * @param secUsrId the starting user ID for the browse operation;
     *                 records with this ID or lexicographically greater IDs
     *                 are returned. Pass an empty string to browse from the
     *                 beginning.
     * @param pageable pagination and sorting parameters (page number,
     *                 page size, sort direction). The COBOL screen displays
     *                 a fixed number of rows per page.
     * @return a {@link Page} of {@link UserSecurity} records matching the
     *         criteria, containing page metadata (total elements, total
     *         pages, current page number)
     */
    Page<UserSecurity> findBySecUsrIdGreaterThanEqual(String secUsrId, Pageable pageable);
}
