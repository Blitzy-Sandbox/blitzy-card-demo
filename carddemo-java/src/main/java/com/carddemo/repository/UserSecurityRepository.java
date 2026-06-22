package com.carddemo.repository;

import com.carddemo.model.entity.UserSecurity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the UserSecurity entity (re-platforms the VSAM
 * USRSEC KSDS). Provides keyed lookup of a user by user id for the sign-on flow
 * (password verification is performed by the authentication service) and
 * inherited CRUD for user administration.
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    Optional<UserSecurity> findBySecUsrId(String secUsrId);

    /**
     * Returns one page of users whose user id is greater than or equal to {@code secUsrIdStart},
     * ordered by the {@link Pageable}'s sort (ascending user id for the browse). This is the
     * "start-at" filter of the COBOL user-list program {@code COUSR00C}: its
     * {@code STARTBR-USER-SEC-FILE} positions the CICS browse on the entered user id with
     * {@code GTEQ} (greater-than-or-equal) and the {@code READNEXT} loop reads forward, so the list
     * begins at the supplied id (or the next existing id when an exact match is absent) rather than
     * performing an exact-match lookup. User ids are 8-character alphanumeric keys, so the
     * comparison is the raw lexicographic order of the key (no numeric normalization), preserving
     * the VSAM key order browsed by the COBOL program.
     *
     * @param secUsrIdStart the inclusive lower-bound user id (the start-at key)
     * @param pageable      the page request (page index, fixed page size, and ascending-id sort)
     * @return the page of users with id {@code >= secUsrIdStart}
     */
    Page<UserSecurity> findBySecUsrIdGreaterThanEqual(String secUsrIdStart, Pageable pageable);
}
