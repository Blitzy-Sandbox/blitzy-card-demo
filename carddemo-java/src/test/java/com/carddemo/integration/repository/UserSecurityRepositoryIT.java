package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
    void seedContainsFiveAdminAndFiveStandardUsers() {
        List<UserSecurity> all = userSecurityRepository.findAll();

        long adminCount = all.stream().filter(u -> u.getSecUsrType() == UserType.ADMIN).count();
        long userCount = all.stream().filter(u -> u.getSecUsrType() == UserType.USER).count();

        assertThat(adminCount).isEqualTo(5L);
        assertThat(userCount).isEqualTo(5L);
    }
}
