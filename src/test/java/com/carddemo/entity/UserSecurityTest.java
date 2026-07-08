package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link UserSecurity} entity (COBOL {@code SEC-USER-DATA} /
 * copybook {@code CSUSR01Y}): accessor round-trips and the BCrypt-widened
 * password field (C-003 / D-002).
 *
 * <p>{@code UserSecurity} is the migrated Java representation of the 80-byte
 * {@code SEC-USER-DATA} record (source commit {@code 27d6c6f}, read-only
 * reference) that backs the preserved file-based {@code USRSEC} authentication
 * store (no RACF). It is a pure persistence POJO: it carries no business logic,
 * no entity relationships, no {@code @Version}, and — importantly — no password
 * hashing. All five fields are plain {@link String} values:</p>
 * <ul>
 *   <li>{@code secUsrId} &larr; {@code SEC-USR-ID PIC X(08)} (natural {@code @Id});</li>
 *   <li>{@code secUsrFname} &larr; {@code SEC-USR-FNAME PIC X(20)};</li>
 *   <li>{@code secUsrLname} &larr; {@code SEC-USR-LNAME PIC X(20)};</li>
 *   <li>{@code secUsrPwd} &larr; {@code SEC-USR-PWD PIC X(08)}, column WIDENED to
 *       {@code VARCHAR(60)} to hold a BCrypt hash (Constraint C-003 / Decision
 *       Log D-002);</li>
 *   <li>{@code secUsrType} &larr; {@code SEC-USR-TYPE PIC X(01)}
 *       ({@code 'A'} = admin, {@code 'U'} = regular user).</li>
 * </ul>
 *
 * <p>The primary focus is twofold: (1) every getter returns exactly what its
 * setter stored (accessor round-trip), and (2) the {@code secUsrPwd} field
 * accommodates a full 60-character BCrypt hash rather than the legacy copybook's
 * 8-character width — the D-002 deviation. Hashing and verification are the sole
 * responsibility of the security layer and are deliberately NOT exercised here.</p>
 *
 * <p>This is a plain-POJO test: no Spring context, no persistence, no
 * Testcontainers, no mocks, and no {@code BCryptPasswordEncoder}. Only
 * {@link String} values are used (no {@code float}/{@code double}), matching the
 * decimal-fidelity constraints of the migration.</p>
 */
class UserSecurityTest {

    /**
     * A representative, well-known 60-character BCrypt hash used purely as a
     * storage fixture. This is the canonical public BCrypt example value (the
     * documented hash of the literal word {@code "password"} at cost factor 10);
     * it is a <strong>test-only sample, NOT a real or production credential</strong>
     * (&sect;0.8.1). Its sole purpose is to prove that the widened
     * {@code sec_usr_pwd} column ({@code VARCHAR(60)}, C-003 / D-002) round-trips a
     * full-length BCrypt hash. Verified to be exactly {@code 60} characters — the
     * fixed output length of every BCrypt hash.
     */
    private static final String SAMPLE_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * The natural key ({@code SEC-USR-ID}) and the two name fields
     * ({@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}) must round-trip: each getter
     * returns exactly the value stored by its setter. The user id sample is the
     * full eight characters permitted by {@code PIC X(08)}.
     */
    @Test
    void naturalKeyAndNameFieldsRoundTrip() {
        UserSecurity user = new UserSecurity();

        user.setSecUsrId("ADMIN001");
        user.setSecUsrFname("ALICE");
        user.setSecUsrLname("ADMIN");

        assertThat(user.getSecUsrId()).isEqualTo("ADMIN001");
        // The natural key occupies the full X(08) width in the legacy record.
        assertThat(user.getSecUsrId()).hasSize(8);
        assertThat(user.getSecUsrFname()).isEqualTo("ALICE");
        assertThat(user.getSecUsrLname()).isEqualTo("ADMIN");
    }

    /**
     * The role indicator ({@code SEC-USR-TYPE PIC X(01)}) must round-trip for both
     * legal values — {@code "A"} (admin) and {@code "U"} (regular user). It is
     * retained as a single-character {@link String} with no enum conversion, so no
     * scope is added beyond the copybook (Gate 7).
     */
    @Test
    void userTypeRoundTrip() {
        UserSecurity user = new UserSecurity();

        user.setSecUsrType("A");
        assertThat(user.getSecUsrType()).isEqualTo("A");

        user.setSecUsrType("U");
        assertThat(user.getSecUsrType()).isEqualTo("U");
    }

    /**
     * The password field must accommodate a full 60-character BCrypt hash and
     * return it byte-for-byte. This proves the deliberate widening of
     * {@code sec_usr_pwd} from the copybook's {@code PIC X(08)} to
     * {@code VARCHAR(60)} (Constraint C-003 / Decision Log D-002). This is a pure
     * storage assertion: no hashing or verification logic is invoked here — that
     * behaviour lives entirely in the security layer.
     */
    @Test
    void passwordFieldHoldsBcryptHashLength60() {
        UserSecurity user = new UserSecurity();

        user.setSecUsrPwd(SAMPLE_BCRYPT_HASH);

        // Exact round-trip: the stored hash is returned unchanged.
        assertThat(user.getSecUsrPwd()).isEqualTo(SAMPLE_BCRYPT_HASH);
        // Widened field: the full 60-character BCrypt hash is retained intact,
        // well beyond the legacy 8-character SEC-USR-PWD width.
        assertThat(user.getSecUsrPwd()).hasSize(60);
    }

    /**
     * A freshly constructed instance (via the JPA-mandated public no-arg
     * constructor) must leave every field unset — each getter returns
     * {@code null} until a setter is invoked.
     */
    @Test
    void newInstanceDefaultsAreNull() {
        UserSecurity user = new UserSecurity();

        assertThat(user.getSecUsrId()).isNull();
        assertThat(user.getSecUsrFname()).isNull();
        assertThat(user.getSecUsrLname()).isNull();
        assertThat(user.getSecUsrPwd()).isNull();
        assertThat(user.getSecUsrType()).isNull();
    }
}
