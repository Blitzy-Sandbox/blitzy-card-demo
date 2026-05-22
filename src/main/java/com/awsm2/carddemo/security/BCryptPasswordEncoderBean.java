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
package com.awsm2.carddemo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Spring configuration that exposes a {@link BCryptPasswordEncoder} as the
 * application's sole {@link PasswordEncoder} bean.
 *
 * <p>This bean replaces the plaintext password storage pattern from the source
 * mainframe system. In the source COBOL implementation, {@code app/cpy/CSUSR01Y.cpy}
 * declared {@code SEC-USR-PWD PIC X(08)} &mdash; an 8-character plaintext password
 * field &mdash; and {@code app/cbl/COSGN00C.cbl} line 223
 * ({@code IF SEC-USR-PWD = WS-USER-PWD}) performed a direct byte-for-byte plaintext
 * comparison of the stored password against the user-entered value. The Java target
 * stores BCrypt-hashed passwords in the {@code user_security.sec_usr_pwd}
 * {@code VARCHAR(60)} column (see Flyway migration
 * {@code V010__create_user_security.sql}) and uses the encoder published by this
 * bean for both hashing (when users are created or updated) and verification
 * (during signon).</p>
 *
 * <p>The BCrypt strength is set to {@value #BCRYPT_STRENGTH} to align with the
 * {@code UserSecurity} JPA entity specification and the V010 schema migration that
 * documents BCrypt(strength=12) as the canonical hashing parameter for this
 * application. Strength {@value #BCRYPT_STRENGTH} translates to approximately
 * 250&ndash;500ms per hash on modern server hardware &mdash; slow enough to
 * materially impede brute-force attacks while fast enough not to degrade
 * interactive signon latency. PCI-DSS-aligned salted hashing is provided
 * automatically by BCrypt (each hash carries its own per-record salt).</p>
 *
 * <p>This bean is deliberately stateless and thread-safe.
 * {@link BCryptPasswordEncoder} is thread-safe by design (its
 * {@code encode}/{@code matches} operations rely on a shared {@code SecureRandom}
 * for salt generation, which is itself thread-safe), so a single instance is
 * shared across the entire application context. Consumers
 * ({@code SignonService}, {@code UserAddService}, {@code UserUpdateService},
 * and {@code SecurityConfig}) autowire the {@link PasswordEncoder} interface
 * rather than the concrete {@link BCryptPasswordEncoder} class so a future
 * algorithm migration (for example to {@code Argon2PasswordEncoder} or
 * {@code DelegatingPasswordEncoder}) requires no source-code changes outside
 * this file.</p>
 *
 * <p>Replaces: {@code CSUSR01Y.cpy} plaintext {@code SEC-USR-PWD PIC X(08)}
 * storage; {@code COSGN00C.cbl} line 223 plaintext password comparison.</p>
 */
@Configuration
public class BCryptPasswordEncoderBean {

    /**
     * BCrypt cost factor (logarithmic; 2<sup>12</sup> = 4096 key-expansion
     * rounds). Aligned with the {@code UserSecurity} JPA entity and the
     * {@code V010__create_user_security.sql} Flyway migration which both
     * specify BCrypt(strength=12) as the application's canonical hashing
     * parameter. The pre-computed BCrypt hashes seeded by
     * {@code V015__seed_default_users.sql} are produced at this strength;
     * changing this value would invalidate those seed hashes at signon.
     *
     * <p>This value is intentionally hardcoded rather than exposed via a
     * {@code @Value}-injected property. Per the project's security policy,
     * the BCrypt strength is a deliberate code change requiring code review
     * &mdash; not a runtime tunable &mdash; so that all callers
     * ({@code SignonService} verification, {@code UserAddService} hashing,
     * {@code UserUpdateService} re-hashing, and the V015 seed-data migration)
     * agree on a single compile-time value.</p>
     */
    private static final int BCRYPT_STRENGTH = 12;

    /**
     * Provides the application-wide {@link PasswordEncoder} implementation.
     *
     * <p>The return type is the {@link PasswordEncoder} interface (not the
     * concrete {@link BCryptPasswordEncoder}) so that consumer code remains
     * decoupled from BCrypt-specific APIs. This bean is the sole
     * {@link PasswordEncoder} in the application context; Spring Security 6
     * resolves the encoder by type for {@code DaoAuthenticationProvider}
     * wiring and for {@code @PreAuthorize}-protected flows.</p>
     *
     * @return a {@link BCryptPasswordEncoder} configured with strength
     *         {@value #BCRYPT_STRENGTH}, exposed as a
     *         {@link PasswordEncoder}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // COBOL: COSGN00C.cbl L223 -- IF SEC-USR-PWD = WS-USER-PWD
        //        Java equivalent (invoked from SignonService):
        //            passwordEncoder.matches(rawPassword, userSecurity.getSecUsrPwd())
        // Replaces: CSUSR01Y.cpy SEC-USR-PWD PIC X(08) plaintext storage.
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
