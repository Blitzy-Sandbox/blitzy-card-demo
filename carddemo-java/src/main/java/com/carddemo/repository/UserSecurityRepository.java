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
     * Returns a single page of users whose user id is greater than or equal to the
     * supplied start key, ordered as requested by the {@link Pageable}.
     *
     * <p>Re-platforms the keyed forward browse of {@code COUSR00C} (user list,
     * transaction {@code CU00}): the CICS {@code STARTBR} on the USRSEC dataset
     * with {@code RIDFLD = SEC-USR-ID} positions at the first record whose key is
     * greater than or equal to the entered user id (GTEQ), and the subsequent
     * {@code READNEXT} loop reads forward from that position. The {@code secUsrId}
     * key is the eight-character primary key, so the lexicographic {@code >=}
     * comparison preserves the VSAM key-ascending browse order. The caller supplies
     * an ascending {@code secUsrId} sort and the ten-rows-per-page size; when no
     * start key is supplied the caller uses the inherited {@code findAll(Pageable)}
     * to browse from the lowest key.</p>
     */
    Page<UserSecurity> findBySecUsrIdGreaterThanEqual(String secUsrId, Pageable pageable);
}
