package com.carddemo.repository;

import com.carddemo.model.entity.UserSecurity;
import java.util.Optional;
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
}
