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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 unit tests for {@link BCryptPasswordEncoderBean} — the Spring
 * configuration that publishes the application-wide
 * {@link PasswordEncoder} bean and replaces the COBOL plaintext password
 * storage from {@code CSUSR01Y.cpy} {@code SEC-USR-PWD PIC X(08)} and
 * the plaintext comparison in {@code COSGN00C.cbl} L223.
 *
 * <h2>QA Final Checkpoint 13 Maj-5 fix</h2>
 * <p>Prior to this test class, the BCrypt encoder was exercised only
 * indirectly through {@code SignonServiceTest}, {@code UserAddServiceTest},
 * and {@code UserSecurityRepositoryTest}. The QA CP13 report (finding
 * Maj-5) requires a dedicated test class to verify the configuration
 * contract — particularly that the BCrypt strength remains pinned at 12
 * (any drift would invalidate the seed hashes from
 * {@code V015__seed_default_users.sql}).</p>
 *
 * <h2>Coverage scope</h2>
 * <ul>
 *   <li><b>Bean instantiation</b> — {@code passwordEncoder()} returns a
 *       non-null {@link BCryptPasswordEncoder} instance.</li>
 *   <li><b>BCrypt strength = 12</b> — verified indirectly via hash
 *       prefix inspection (BCrypt hashes are formatted as
 *       {@code $2a$<strength>$<22-byte-salt><31-byte-hash>}).</li>
 *   <li><b>Hash uniqueness</b> — hashing the same password twice
 *       produces different hashes due to random salt generation.</li>
 *   <li><b>{@code matches()} correctness</b> — verifies a hash against
 *       the original password (success) and a different password
 *       (failure).</li>
 *   <li><b>Empty/blank password handling</b> — both encode and matches
 *       behave correctly with empty strings (BCrypt accepts them).</li>
 *   <li><b>PCI-DSS thread-safety</b> — concurrent encode calls produce
 *       independent hashes that all verify against the original.</li>
 * </ul>
 *
 * @see BCryptPasswordEncoderBean
 */
@DisplayName("BCryptPasswordEncoderBean — QA CP13 Maj-5 dedicated test class")
class BCryptPasswordEncoderBeanTest {

    private final BCryptPasswordEncoderBean config = new BCryptPasswordEncoderBean();
    private final PasswordEncoder encoder = config.passwordEncoder();

    // -------------------------------------------------------------------------
    // Bean instantiation & type contract
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("passwordEncoder() — bean instantiation contract")
    class BeanInstantiationTests {

        @Test
        @DisplayName("returns non-null PasswordEncoder")
        void returnsNonNullEncoder() {
            assertThat(encoder).isNotNull();
        }

        @Test
        @DisplayName("returned instance is a BCryptPasswordEncoder")
        void returnedTypeIsBCrypt() {
            assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);
        }

        @Test
        @DisplayName("passwordEncoder() returns a NEW instance per call (Spring caches the bean)")
        void distinctInvocationsReturnFreshInstances() {
            // This verifies the bean method's contract — Spring decides
            // whether to cache. Direct invocation of the @Bean method
            // returns a fresh instance each time.
            PasswordEncoder e1 = config.passwordEncoder();
            PasswordEncoder e2 = config.passwordEncoder();
            assertThat(e1).isNotSameAs(e2);
        }
    }

    // -------------------------------------------------------------------------
    // BCrypt strength = 12 verification
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("BCrypt strength = 12 (CSUSR01Y schema seed parity)")
    class BCryptStrengthTests {

        @Test
        @DisplayName("hash uses log-rounds 12 ($2a$12$ prefix)")
        void hashUsesStrength12() {
            // BCrypt hash format: $2a$<strength>$<salt+hash>
            // (Spring Security uses $2a$ by default; $2b$ is acceptable too)
            String hash = encoder.encode("anyPassword");

            // The hash must start with $2a$12$ or $2b$12$.
            assertThat(hash)
                    .as("BCrypt strength must be 12 to match seed data and entity spec")
                    .matches("^\\$2[ab]\\$12\\$.*");
        }

        @Test
        @DisplayName("hash length is exactly 60 characters (BCrypt canonical length)")
        void hashLengthIs60() {
            String hash = encoder.encode("anyPassword");
            // BCrypt always produces 60-character hashes:
            //   $2a$12$ (7) + 22-char salt + 31-char hash = 60
            assertThat(hash).hasSize(60);
        }
    }

    // -------------------------------------------------------------------------
    // Hash uniqueness (salt determinism)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Hash uniqueness via per-record salt")
    class HashUniquenessTests {

        @Test
        @DisplayName("encoding the same password twice produces different hashes")
        void sameSecretDifferentSalt() {
            String password = "MySecret123!";
            String hash1 = encoder.encode(password);
            String hash2 = encoder.encode(password);

            assertThat(hash1)
                    .as("BCrypt must use a per-call random salt")
                    .isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("both hashes verify against the original password")
        void differentHashesBothMatch() {
            String password = "MySecret123!";
            String hash1 = encoder.encode(password);
            String hash2 = encoder.encode(password);

            assertThat(encoder.matches(password, hash1)).isTrue();
            assertThat(encoder.matches(password, hash2)).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // matches() correctness — accept/reject behaviour
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("matches() — verification correctness")
    class MatchesCorrectnessTests {

        @Test
        @DisplayName("matches(correct, hash) returns true")
        void correctPasswordMatches() {
            String password = "AdminPass2024";
            String hash = encoder.encode(password);

            assertThat(encoder.matches(password, hash)).isTrue();
        }

        @Test
        @DisplayName("matches(wrong, hash) returns false")
        void wrongPasswordDoesNotMatch() {
            String password = "AdminPass2024";
            String hash = encoder.encode(password);

            assertThat(encoder.matches("wrongPassword", hash)).isFalse();
            assertThat(encoder.matches("AdminPass2025", hash)).isFalse();
            assertThat(encoder.matches("adminPass2024", hash)).isFalse(); // case
        }

        @Test
        @DisplayName("matches(empty, hash_of_nonempty) returns false")
        void emptyDoesNotMatchNonempty() {
            String hash = encoder.encode("nonEmpty");
            assertThat(encoder.matches("", hash)).isFalse();
        }

        @Test
        @DisplayName("matches(password, null) returns false (no NullPointerException)")
        void matchesNullHashReturnsFalse() {
            // Spring's BCryptPasswordEncoder treats a null encodedPassword
            // as a non-match rather than throwing.
            assertThat(encoder.matches("anyPassword", null)).isFalse();
        }

        @Test
        @DisplayName("matches(password, '') returns false (no NullPointerException)")
        void matchesEmptyHashReturnsFalse() {
            assertThat(encoder.matches("anyPassword", "")).isFalse();
        }

        @Test
        @DisplayName("matches(password, malformed_hash) returns false")
        void matchesMalformedHashReturnsFalse() {
            assertThat(encoder.matches("anyPassword", "not-a-bcrypt-hash")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // encode() — input handling
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("encode() — input handling")
    class EncodeInputTests {

        @Test
        @DisplayName("encodes empty string (BCrypt accepts empty input)")
        void encodesEmptyString() {
            String hash = encoder.encode("");

            // BCrypt accepts empty passwords; the hash is well-formed and
            // matches() only the empty string.
            assertThat(hash).matches("^\\$2[ab]\\$12\\$.*");
            assertThat(encoder.matches("", hash)).isTrue();
            assertThat(encoder.matches(" ", hash)).isFalse();
        }

        @Test
        @DisplayName("encodes whitespace password (BCrypt treats it as non-empty)")
        void encodesWhitespacePassword() {
            String hash = encoder.encode("   ");

            assertThat(encoder.matches("   ", hash)).isTrue();
            assertThat(encoder.matches("", hash)).isFalse();
        }

        @Test
        @DisplayName("encodes password at the BCrypt 72-byte boundary")
        void encodesPasswordAtMaxBoundary() {
            // BCrypt operates on at most 72 bytes of input. Spring
            // Security 6.x's BCryptPasswordEncoder accepts inputs up to
            // 72 bytes and throws IllegalArgumentException above that.
            // A 72-character ASCII password (72 bytes UTF-8) is the
            // largest acceptable input.
            String maxPassword = "a".repeat(72);
            String hash = encoder.encode(maxPassword);

            assertThat(hash).matches("^\\$2[ab]\\$12\\$.*");
            assertThat(encoder.matches(maxPassword, hash)).isTrue();
        }

        @Test
        @DisplayName("rejects passwords longer than 72 bytes (Spring Security 6.x policy)")
        void rejectsOverlongPassword() {
            // Spring Security 6.x changed the contract: rather than
            // silently truncating inputs > 72 bytes (the legacy BCrypt
            // behaviour), the encoder now throws IllegalArgumentException
            // to prevent confusing security regressions where two
            // distinct passwords sharing a 72-byte prefix would
            // unintentionally match.
            String overlongPassword = "a".repeat(100);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> encoder.encode(overlongPassword))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("72 bytes");
        }

        @Test
        @DisplayName("encodes Unicode characters correctly")
        void encodesUnicodeCharacters() {
            String unicode = "пароль123日本語🔒";
            String hash = encoder.encode(unicode);

            assertThat(hash).matches("^\\$2[ab]\\$12\\$.*");
            assertThat(encoder.matches(unicode, hash)).isTrue();
            assertThat(encoder.matches("пароль123", hash)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Thread-safety — concurrent encode calls
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Thread-safety — concurrent operation")
    class ThreadSafetyTests {

        @Test
        @DisplayName("concurrent encode produces independent verifiable hashes")
        void concurrentEncodingIsIndependent() throws Exception {
            String password = "ConcurrentTest!";
            int threadCount = 8;
            Thread[] threads = new Thread[threadCount];
            String[] hashes = new String[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                threads[i] = new Thread(() -> hashes[idx] = encoder.encode(password));
                threads[i].start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // All hashes must (a) be unique (different salts), and
            // (b) verify against the original password.
            assertThat(hashes).doesNotContainNull();
            for (int i = 0; i < threadCount; i++) {
                assertThat(encoder.matches(password, hashes[i]))
                        .as("hash %d verifies", i)
                        .isTrue();
                for (int j = i + 1; j < threadCount; j++) {
                    assertThat(hashes[i])
                            .as("hash %d != hash %d (independent salts)", i, j)
                            .isNotEqualTo(hashes[j]);
                }
            }
        }
    }
}
