/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.integration;

import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link UserRepository} against a <strong>real PostgreSQL&nbsp;16</strong>
 * Testcontainer seeded by Flyway (V1 schema, V2 indexes, V3 seed) — no mocks, no H2.
 *
 * <h2>Legacy provenance</h2>
 * The {@code users} table is the relational successor of the file-based VSAM
 * {@code USRSEC} security file (KSDS keyed on the 8-character {@code SEC-USR-ID},
 * 80-byte fixed records), defined by JCL {@code app/jcl/DUSRSECJ.jcl} and laid
 * out by copybook {@code app/cpy/CSUSR01Y.cpy} ({@code SEC-USER-DATA}) at source
 * commit {@code 27d6c6f}. {@link UserRepository} replaces the keyed VSAM access
 * paths the COBOL programs used:
 * <ul>
 *   <li>{@code COSGN00C} sign-on — keyed {@code READ} of {@code USRSEC} then a
 *       password compare ({@code IF SEC-USR-PWD = WS-USER-PWD}) &rarr;
 *       {@code findById(..)} feeding {@code AuthService}; the {@code NOTFND}
 *       branch ("User not found") &rarr; an empty {@link Optional}.</li>
 *   <li>{@code COUSR00C} user-list browse (PF7/PF8, 10 rows/screen) &rarr;
 *       {@code findAll(Pageable)} with {@code Sort.by("userId")}.</li>
 *   <li>{@code COUSR01C}/{@code COUSR02C}/{@code COUSR03C} add/update/delete
 *       &rarr; {@code save(..)} and {@code deleteById(..)}.</li>
 * </ul>
 *
 * <h2>BCrypt credential upgrade (constraint C-003)</h2>
 * The legacy {@code SEC-USR-PWD PIC X(08)} stored an 8-character plaintext
 * password (initial value {@code "PASSWORD"} in {@code DUSRSECJ}). Per the
 * explicitly-permitted constraint C-003 the migration widens that column to
 * {@code VARCHAR(60)} and stores a <em>BCrypt hash</em>; this suite proves that
 * the seeded rows hold BCrypt hashes (never plaintext) and that a seeded hash
 * still verifies against the documented legacy password, so the sign-on success
 * path is preserved with 100% behavioural parity.
 *
 * <h2>Data isolation</h2>
 * Read-only tests assert against the committed Flyway seed (ten {@code USRSEC}
 * users: {@code ADMIN001}–{@code ADMIN005} type {@code A},
 * {@code USER0001}–{@code USER0005} type {@code U}). The mutating CRUD tests are
 * {@link Transactional} so Spring rolls their transaction back, leaving the
 * shared seed intact for the end-to-end {@code *IT} classes that authenticate as
 * {@code ADMIN001}/{@code USER0001}. The seeded user-id and user-type constants
 * are inherited from {@link AbstractIntegrationIT} so they never drift from the
 * migration's canonical values.
 */
@DisplayName("UserRepository IT — PostgreSQL 16 + Flyway-seeded USRSEC (CSUSR01Y / COSGN00C / DUSRSECJ)")
class UserRepositoryIT extends AbstractIntegrationIT {

    /**
     * Canonical BCrypt hash shape: a {@code $2a$}/{@code $2b$}/{@code $2y$}
     * version tag, a two-digit cost factor, then a 22-character salt plus a
     * 31-character digest (53 radix-64 characters) — 60 characters in total,
     * exactly matching the {@code password VARCHAR(60)} column width.
     */
    private static final Pattern BCRYPT_PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    /** Fixed BCrypt hash width; the C-003-widened {@code password} column is {@code VARCHAR(60)}. */
    private static final int BCRYPT_HASH_LENGTH = 60;

    /**
     * The documented legacy initial sign-on password taken verbatim from the
     * frozen COBOL corpus ({@code DUSRSECJ.jcl} in-stream {@code SYSUT1} data).
     * This is the publicly-known legacy default used only to prove the C-003
     * upgrade — it is never a live credential, and V3 persists its BCrypt hash
     * rather than this plaintext.
     */
    private static final String LEGACY_INITIAL_PASSWORD = "PASSWORD";

    @Autowired
    private UserRepository userRepository;

    // =====================================================================================
    // Phase 1 — sign-on path lookups (<- COSGN00C keyed READ / AuthService.findById)
    // =====================================================================================

    @Test
    @DisplayName("findById(ADMIN001) returns the seeded admin holding a BCrypt hash, not plaintext")
    void findByIdReturnsSeededAdminAsBcryptHash() {
        Optional<User> admin = userRepository.findById(SEEDED_ADMIN_USER_ID);

        assertThat(admin)
                .as("seeded administrator %s must exist (Flyway V3 <- DUSRSECJ)", SEEDED_ADMIN_USER_ID)
                .isPresent();
        User user = admin.orElseThrow();
        assertThat(user.getUserId()).isEqualTo(SEEDED_ADMIN_USER_ID);
        assertThat(user.getUserType())
                .as("ADMIN001 user-type 'A' drives ROLE_ADMIN")
                .isEqualTo(SEEDED_ADMIN_USER_TYPE);
        assertBcryptHash(user.getPassword());
        assertThat(user.getPassword())
                .as("C-003: the stored credential must not be the legacy plaintext")
                .isNotEqualTo(LEGACY_INITIAL_PASSWORD);
    }

    @Test
    @DisplayName("findById(USER0001) returns the seeded standard user (user-type 'U')")
    void findByIdReturnsSeededStandardUser() {
        Optional<User> standard = userRepository.findById(SEEDED_STANDARD_USER_ID);

        assertThat(standard)
                .as("seeded standard user %s must exist (Flyway V3 <- DUSRSECJ)", SEEDED_STANDARD_USER_ID)
                .isPresent();
        User user = standard.orElseThrow();
        assertThat(user.getUserType()).isEqualTo(SEEDED_STANDARD_USER_TYPE);
        assertBcryptHash(user.getPassword());
    }

    @Test
    @DisplayName("findById(unknown id) is empty — the COBOL NOTFND \"User not found\" branch")
    void findByIdForUnknownUserReturnsEmpty() {
        // A non-seeded but well-formed 8-character key (the VSAM SEC-USR-ID was
        // space-padded to 8); COSGN00C routes this RESP=NOTFND to "User not found".
        assertThat(userRepository.findById("NOSUCHID"))
                .as("a non-seeded key maps to the COSGN00C NOTFND branch")
                .isEmpty();
    }

    @Test
    @DisplayName("existsById reflects seeded presence (drives the UserAddService duplicate check)")
    void existsByIdReflectsSeededAndAbsentUsers() {
        assertThat(userRepository.existsById(SEEDED_ADMIN_USER_ID))
                .as("the seeded admin must be reported present")
                .isTrue();
        assertThat(userRepository.existsById("ZZZZZZZZ"))
                .as("an absent key must be reported missing (a new user-id is therefore unique)")
                .isFalse();
    }

    @Test
    @DisplayName("seeded BCrypt hash verifies against the legacy plaintext (C-003 sign-on parity)")
    void seededAdminPasswordVerifiesAgainstLegacyPlaintext() {
        User admin = userRepository.findById(SEEDED_ADMIN_USER_ID).orElseThrow();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        assertThat(encoder.matches(LEGACY_INITIAL_PASSWORD, admin.getPassword()))
                .as("BCrypt(stored) must verify against the legacy initial password "
                        + "(COSGN00C SEC-USR-PWD = WS-USER-PWD success path)")
                .isTrue();
        assertThat(encoder.matches("WRONGPWD", admin.getPassword()))
                .as("a wrong password must fail verification (COSGN00C \"Wrong Password\" path)")
                .isFalse();
    }

    // =====================================================================================
    // Phase 2 — browse pagination (<- COUSR00C user-list, UserListService PAGE_SIZE = 10)
    // =====================================================================================

    @Test
    @DisplayName("findAll(page 0, size 10, sort userId) honours the page window and shows the seeded users")
    void findAllFirstPageHonoursPageSizeAndContainsSeededUsers() {
        // Sort.by("userId") preserves the legacy SEC-USR-ID browse order (COUSR00C PF7/PF8).
        Page<User> firstPage = userRepository.findAll(PageRequest.of(0, 10, Sort.by("userId")));

        assertThat(firstPage.getContent())
                .as("a page of size 10 must never exceed the COUSR00C screen window")
                .hasSizeLessThanOrEqualTo(10);
        assertThat(firstPage.getTotalElements())
                .as("Flyway V3 seeds at least the admin + standard user")
                .isGreaterThanOrEqualTo(2L);

        List<String> pageUserIds = firstPage.getContent().stream().map(User::getUserId).toList();
        assertThat(pageUserIds)
                .as("the seeded admin and standard user appear in the first page")
                .contains(SEEDED_ADMIN_USER_ID, SEEDED_STANDARD_USER_ID);
    }

    // =====================================================================================
    // Phase 3 — CRUD (<- COUSR01C add / COUSR02C update / COUSR03C delete). Each mutating
    // test is @Transactional so Spring rolls it back, leaving the shared seed untouched.
    // =====================================================================================

    @Test
    @Transactional
    @DisplayName("save persists a new user with its BCrypt hash intact (WRITE <- COUSR01C)")
    void saveNewUserPersistsBcryptHash() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("ITTESTPW");
        User created = new User("ITSAVE01", "INTEG", "SAVE", hash, SEEDED_STANDARD_USER_TYPE);

        userRepository.saveAndFlush(created);

        // A raw JDBC read proves the row physically reached PostgreSQL (not a first-level
        // cache and certainly not H2); the JdbcTemplate shares the test transaction's
        // connection so it sees the flushed INSERT.
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password FROM users WHERE user_id = ?", String.class, "ITSAVE01");
        assertThat(storedHash).isEqualTo(hash);

        Optional<User> reloaded = userRepository.findById("ITSAVE01");
        assertThat(reloaded).isPresent();
        User loaded = reloaded.orElseThrow();
        assertThat(loaded.getUserType()).isEqualTo(SEEDED_STANDARD_USER_TYPE);
        assertBcryptHash(loaded.getPassword());
        assertThat(loaded.getPassword())
                .as("the stored hash must survive the round trip byte-for-byte")
                .isEqualTo(hash);
        assertThat(encoder.matches("ITTESTPW", loaded.getPassword()))
                .as("the persisted hash still verifies against the originating password")
                .isTrue();
    }

    @Test
    @Transactional
    @DisplayName("save REWRITE reflects user-type and password changes on reload (no @Version) (<- COUSR02C)")
    void updateUserTypeAndPasswordReflectedOnReload() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        userRepository.saveAndFlush(new User(
                "ITUPDT01", "INTEG", "UPDATE", encoder.encode("ORIGINAL"), SEEDED_STANDARD_USER_TYPE));

        User toUpdate = userRepository.findById("ITUPDT01").orElseThrow();
        String newHash = encoder.encode("CHANGED1");
        toUpdate.setUserType(SEEDED_ADMIN_USER_TYPE);
        toUpdate.setPassword(newHash);
        userRepository.saveAndFlush(toUpdate);

        User reloaded = userRepository.findById("ITUPDT01").orElseThrow();
        assertThat(reloaded.getUserType())
                .as("the user-type change (U -> A) must be reflected on reload")
                .isEqualTo(SEEDED_ADMIN_USER_TYPE);
        assertThat(reloaded.getPassword())
                .as("the rewritten BCrypt hash must be reflected on reload")
                .isEqualTo(newHash);
        assertThat(encoder.matches("CHANGED1", reloaded.getPassword())).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("deleteById removes the user; subsequent findById is empty (DELETE <- COUSR03C)")
    void deleteByIdRemovesUser() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        userRepository.saveAndFlush(new User(
                "ITDELT01", "INTEG", "DELETE", encoder.encode("TODELETE"), SEEDED_STANDARD_USER_TYPE));
        assertThat(userRepository.existsById("ITDELT01"))
                .as("the user must exist before deletion")
                .isTrue();

        userRepository.deleteById("ITDELT01");
        userRepository.flush();

        assertThat(userRepository.findById("ITDELT01"))
                .as("after DELETE the keyed lookup must be empty")
                .isEmpty();
        assertThat(userRepository.existsById("ITDELT01")).isFalse();
    }

    // =====================================================================================
    // Phase 4 — security hygiene (C-003: no plaintext credentials anywhere in the seed)
    // =====================================================================================

    @Test
    @DisplayName("no seeded user stores a plaintext password — every credential is a 60-char BCrypt hash (C-003)")
    void noSeededUserStoresPlaintextPassword() {
        List<User> users = userRepository.findAll();

        assertThat(users).as("Flyway V3 seeds the USRSEC users").isNotEmpty();
        assertThat(users).allSatisfy(user ->
                assertThat(user.getPassword())
                        .as("user %s must store a BCrypt hash, never the legacy plaintext", user.getUserId())
                        .isNotNull()
                        .hasSize(BCRYPT_HASH_LENGTH)
                        .matches(BCRYPT_PATTERN)
                        .isNotEqualTo(LEGACY_INITIAL_PASSWORD));
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /**
     * Asserts that a stored credential is a 60-character BCrypt hash
     * ({@code $2a$}/{@code $2b$}/{@code $2y$}) and therefore never a plaintext
     * value — the recurring C-003 "no plaintext credentials" check.
     *
     * @param password the stored credential to validate
     */
    private static void assertBcryptHash(String password) {
        assertThat(password)
                .as("credential must be a BCrypt hash (C-003), not plaintext")
                .isNotNull()
                .hasSize(BCRYPT_HASH_LENGTH)
                .matches(BCRYPT_PATTERN);
    }
}
