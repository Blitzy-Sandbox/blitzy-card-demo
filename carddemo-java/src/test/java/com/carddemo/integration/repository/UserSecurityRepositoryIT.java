package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Integration tests for {@link UserSecurityRepository} against a real PostgreSQL 16 Testcontainer.
 * Verifies the VSAM USRSEC KSDS re-platforms to PostgreSQL with the findBySecUsrId sign-on lookup,
 * the UserType converter, and constraint C-003 (plaintext passwords upgraded to BCrypt hashes).
 */
class UserSecurityRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    @Test
    void seedDataLoadsAllUsers() {
        assertThat(userSecurityRepository.count()).isEqualTo(10L);
        assertThat(userSecurityRepository.findAll()).hasSize(10);
    }

    @Test
    void findBySecUsrIdReturnsAdminUser() {
        Optional<UserSecurity> found = userSecurityRepository.findBySecUsrId("ADMIN001");

        assertThat(found).isPresent();
        assertThat(found.get().getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(found.get().getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    @Test
    void findBySecUsrIdReturnsStandardUser() {
        Optional<UserSecurity> found = userSecurityRepository.findBySecUsrId("USER0001");

        assertThat(found).isPresent();
        assertThat(found.get().getSecUsrId()).isEqualTo("USER0001");
        assertThat(found.get().getSecUsrType()).isEqualTo(UserType.USER);
    }

    @Test
    void findBySecUsrIdReturnsEmptyForUnknownUser() {
        assertThat(userSecurityRepository.findBySecUsrId("NOSUCHUSER")).isEmpty();
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        for (UserSecurity user : userSecurityRepository.findAll()) {
            assertThat(user.getSecUsrPwd()).isNotNull();
            assertThat(user.getSecUsrPwd()).hasSize(60);
            assertThat(user.getSecUsrPwd()).startsWith("$2");
            assertThat(user.getSecUsrPwd()).isNotEqualTo("PASSWORD");
        }
    }

    @Test
    void findBySecUsrIdGreaterThanEqualStartsAtKeyAndReadsForward() {
        // COUSR00C STARTBR (GTEQ): the browse begins at the supplied user id and reads forward in
        // ascending key order. User ids are 8-char alphanumeric, so "USER0001" lexicographically
        // follows all "ADMIN00x" ids; starting at it yields exactly the five USER000x rows.
        Page<UserSecurity> page = userSecurityRepository.findBySecUsrIdGreaterThanEqual(
                "USER0001", PageRequest.of(0, 10, Sort.by("secUsrId").ascending()));

        assertThat(page.getTotalElements()).isEqualTo(5L);
        assertThat(page.getContent()).extracting(UserSecurity::getSecUsrId)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
    }

    @Test
    void findBySecUsrIdGreaterThanEqualIncludesExactMatchAndLaterKeys() {
        // Starting at ADMIN003 includes ADMIN003 itself (>=) and every later key: ADMIN004/005 and
        // all five USER000x rows (8 total).
        Page<UserSecurity> page = userSecurityRepository.findBySecUsrIdGreaterThanEqual(
                "ADMIN003", PageRequest.of(0, 10, Sort.by("secUsrId").ascending()));

        assertThat(page.getTotalElements()).isEqualTo(8L);
        assertThat(page.getContent()).extracting(UserSecurity::getSecUsrId)
                .containsExactly("ADMIN003", "ADMIN004", "ADMIN005",
                        "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
    }

    @Test
    void findBySecUsrIdGreaterThanEqualReturnsEmptyWhenStartIsPastLastKey() {
        Page<UserSecurity> page = userSecurityRepository.findBySecUsrIdGreaterThanEqual(
                "ZZZZZZZZ", PageRequest.of(0, 10, Sort.by("secUsrId").ascending()));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void seedContainsFiveAdminAndFiveStandardUsers() {
        List<UserSecurity> all = userSecurityRepository.findAll();

        long adminCount = all.stream().filter(u -> u.getSecUsrType() == UserType.ADMIN).count();
        long userCount = all.stream().filter(u -> u.getSecUsrType() == UserType.USER).count();

        assertThat(adminCount).isEqualTo(5L);
        assertThat(userCount).isEqualTo(5L);
    }
}
