/*
 * ******************************************************************
 * Program     : CardDemoUserDetailsServiceTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test
 * Function    : Pins the COSGN00C credential check - BOTH the user identifier and
 *               the password are upper-cased before comparison, the stored value
 *               is a BCrypt digest rather than plaintext, and an absent row is
 *               indistinguishable from a wrong password.
 * Source      : app/cbl/COSGN00C.cbl:L118-L126 (empty-field refusals),
 *               :L222-L246 (the credential comparison),
 *               :L247-L256 (NOTFND and the I/O failure path) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy (the 80-byte SEC-USR record) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L26-L28 (CDEMO-USER-TYPE 'A'/'U') @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.security.CardDemoUserDetailsService;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Unit test for {@link CardDemoUserDetailsService}, the Java form of the {@code COSGN00C} credential check.
 *
 * <h2>What it does</h2>
 *
 * <p>Three properties here are easy to implement <em>almost</em> correctly, and each would produce a system
 * that authenticates most users most of the time while diverging from the system of record.
 *
 * <p>First, <strong>both</strong> the user identifier and the password are upper-cased before comparison. The
 * legacy program moves each presented field through an upper-casing function, so {@code password} and
 * {@code PASSWORD} are the same credential on the mainframe. Upper-casing only the identifier - the intuitive
 * half, since identifiers are keys - would reject every lower-case password the legacy system accepted. That
 * is asserted directly with mixed-case input.
 *
 * <p>Second, the stored value is a <strong>BCrypt digest</strong>, never the plaintext of
 * {@code app/jcl/DUSRSECJ.jcl}. This test uses a <em>real</em> {@link BCryptPasswordEncoder} at the pinned
 * strength rather than a stubbed encoder, because a stub would assert the test's own idea of matching and
 * would pass just as happily against a plaintext comparison.
 *
 * <p>Third, an absent row and a wrong password are deliberately <strong>indistinguishable</strong> to the
 * caller: both answer with the same rejection text. Leaking which of the two occurred would turn the sign-on
 * endpoint into a user-enumeration oracle. The exception <em>types</em> differ, which Spring Security needs
 * internally, but the messages do not, and that is asserted.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run alone with
 * {@code ./mvnw -B -ntp -o test -Dtest=CardDemoUserDetailsServiceTest -Djacoco.skip=true}, or with the unit
 * tier via {@code ./mvnw -B -ntp test}. The {@code -Dtest} separator is a comma, never a plus.
 *
 * <p>The repository is a Mockito double; the encoder is real. BCrypt at strength 10 is deliberately slow, so
 * the fixture hash is computed once in a static initialiser rather than per test.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>No configuration key participates. The BCrypt strength is 10, matching the value
 * {@code SecurityConfig} requires; a hash produced at any other strength would be refused by the
 * {@link UserSecurity} constructor, which is itself part of the contract.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in {@code aLowerCasePasswordIsAccepted} means the password normalisation was dropped.
 *       Restore it: the legacy program upper-cases both fields, and this is a parity requirement rather than
 *       a convenience.</li>
 *   <li>A failure in the enumeration test means the rejection messages diverged, re-enabling user
 *       enumeration. Keep both paths answering with the same text.</li>
 *   <li>A failure in the store-failure test means a data-access fault is being reported as a credential
 *       rejection, which would tell a caller their password is wrong when the database is simply down.</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CardDemoUserDetailsService - the COSGN00C credential check")
class CardDemoUserDetailsServiceTest {

    /** The BCrypt strength {@code SecurityConfig} requires; any other value is refused by the entity. */
    private static final int BCRYPT_STRENGTH = 10;

    /** {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy}: eight bytes, so eight characters. */
    private static final int CREDENTIAL_WIDTH = 8;

    /** Letters drawn from when generating a credential, so every character survives upper-casing. */
    private static final int ALPHABET_SIZE = 26;

    /**
     * A synthetic credential, generated per run, standing in for the seeded plaintext.
     *
     * <p><strong>Why generated rather than written down.</strong> All ten principals of
     * {@code app/jcl/DUSRSECJ.jcl} carry one shared plaintext, and this class must not name
     * that value as a constant. Nothing about this class needs the real value: what is under test is the
     * upper-casing of both fields and the BCrypt verification, and a generated credential exercises both
     * identically. Naming it, by contrast, would put a working credential for the shipped demo seed into a
     * tracked file - and would defeat the security gate's own "no plaintext anywhere" claim.
     * Rule 1 clause D admits no sample exception.
     *
     * <p>Eight upper-case characters, because {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy}
     * is eight bytes and the source upper-cases what it receives; the width and the case are the properties
     * the subject actually depends on. The value differs on every run, so no assertion can come to depend on
     * a particular one.
     */
    private static final String SYNTHETIC_PLAINTEXT = syntheticCredential();

    /**
     * A credential the seeded digest does not verify against, derived so it cannot collide.
     *
     * <p>Built by rotating each character of {@link #SYNTHETIC_PLAINTEXT} one position along the alphabet,
     * which changes every byte while keeping the width and the case class. A literal here would be a second
     * credential-shaped constant, and a randomly generated one could in principle collide.
     */
    private static final String WRONG_PLAINTEXT = rotated(SYNTHETIC_PLAINTEXT);

    /** The eight-character {@code SEC-USR-ID} of the first seeded standard user. */
    private static final String USER_ID = "USER0001";

    /** The eight-character {@code SEC-USR-ID} of the first seeded administrator. */
    private static final String ADMIN_ID = "ADMIN001";

    /**
     * The single rejection text shared by the absent-row and wrong-password paths.
     *
     * <p>It is restated here rather than read from the service because the service keeps it private
     * deliberately - the constant is what makes the two paths indistinguishable, and a test that read it
     * from the subject could not detect the two drifting apart.
     */
    private static final String REJECTION_TEXT =
            "Sign-on failed. Check the user identifier and password and try again.";

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    /** Computed once: BCrypt at strength 10 is intentionally expensive. */
    private static final String SEEDED_HASH = ENCODER.encode(SYNTHETIC_PLAINTEXT);

    @Mock private UserSecurityRepository userSecurityRepository;

    private CardDemoUserDetailsService service;

    @BeforeEach
    void setUp() {
        this.service = new CardDemoUserDetailsService(this.userSecurityRepository, ENCODER);
    }

    private static UserSecurity seededUser(final String id, final UserType type) {
        return new UserSecurity(id, "LAWRENCE", "THOMAS", SEEDED_HASH, type);
    }

    /**
     * Generates one eight-character upper-case credential for this run.
     *
     * <p>{@link SecureRandom} rather than {@link java.util.Random}: this value is BCrypt-hashed and compared,
     * so it behaves as a credential inside the test and is generated as one. Only the twenty-six upper-case
     * letters are drawn, which keeps the value inside the {@code PIC X(08)} character set the source's own
     * {@code FUNCTION UPPER-CASE} normalisation produces.
     *
     * @return the generated credential, never {@code null} and always eight upper-case letters
     */
    private static String syntheticCredential() {
        final SecureRandom random = new SecureRandom();
        final StringBuilder generated = new StringBuilder(CREDENTIAL_WIDTH);
        for (int position = 0; position < CREDENTIAL_WIDTH; position++) {
            generated.append((char) ('A' + random.nextInt(ALPHABET_SIZE)));
        }
        return generated.toString();
    }

    /**
     * Rotates every upper-case letter one position along the alphabet, wrapping {@code Z} to {@code A}.
     *
     * <p>Used to derive a credential that is guaranteed not to verify against {@link #SEEDED_HASH} while
     * keeping the width and the case class of the real one, so the rejection paths are exercised with an
     * input shaped like a genuine attempt rather than with an obviously malformed one.
     *
     * @param value the credential to rotate; must not be {@code null}
     * @return the rotated credential, never equal to {@code value}
     */
    private static String rotated(final String value) {
        final StringBuilder derived = new StringBuilder(value.length());
        for (final char letter : value.toCharArray()) {
            derived.append(letter == 'Z' ? 'A' : (char) (letter + 1));
        }
        return derived.toString();
    }

    /**
     * The case variants of the generated credential, as parameterised-test arguments.
     *
     * <p>Derived from {@link #SYNTHETIC_PLAINTEXT} rather than written out, so the arguments follow the
     * generated value instead of pinning a particular one: all lower case, initial capital only, and
     * alternating case. Each must be accepted, because {@code app/cbl/COSGN00C.cbl:L219-L220} upper-cases the
     * password as well as the identifier before comparing.
     *
     * @return three case variants of the run's credential
     */
    private static Stream<String> caseVariantsOfTheCredential() {
        final String lower = SYNTHETIC_PLAINTEXT.toLowerCase(Locale.ROOT);
        final StringBuilder alternating = new StringBuilder(lower);
        for (int position = 0; position < alternating.length(); position += 2) {
            alternating.setCharAt(position, Character.toUpperCase(alternating.charAt(position)));
        }
        return Stream.of(
                lower,
                Character.toUpperCase(lower.charAt(0)) + lower.substring(1),
                alternating.toString());
    }

    private void storeHolds(final String id, final UserType type) {
        when(this.userSecurityRepository.findById(id)).thenReturn(Optional.of(seededUser(id, type)));
    }

    @Nested
    @DisplayName("loadUserByUsername - the lookup Spring Security calls")
    class LoadUserByUsername {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("an absent identifier is refused before the store is touched")
        void anAbsentIdentifierIsRefusedBeforeTheStoreIsTouched(final String blank) {
            assertThatExceptionOfType(UsernameNotFoundException.class)
                    .isThrownBy(() -> service.loadUserByUsername(blank))
                    .withMessage("Please enter a user identifier.");
        }

        @Test
        @DisplayName("the identifier is upper-cased before the key lookup")
        void theIdentifierIsUpperCasedBeforeTheLookup() {
            storeHolds(USER_ID, UserType.USER);

            service.loadUserByUsername("user0001");

            verify(userSecurityRepository)
                    .findById("USER0001");
        }

        @Test
        @DisplayName("an unknown identifier is refused with the shared rejection text")
        void anUnknownIdentifierIsRefused() {
            when(userSecurityRepository.findById("NOSUCHID")).thenReturn(Optional.empty());

            assertThatExceptionOfType(UsernameNotFoundException.class)
                    .isThrownBy(() -> service.loadUserByUsername("NOSUCHID"))
                    .withMessage(REJECTION_TEXT);
        }

        @Test
        @DisplayName("a standard user resolves to ROLE_USER")
        void aStandardUserResolvesToRoleUser() {
            storeHolds(USER_ID, UserType.USER);

            final UserDetails details = service.loadUserByUsername(USER_ID);

            assertThat(details.getUsername()).isEqualTo(USER_ID);
            assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("an administrator resolves to ROLE_ADMIN")
        void anAdministratorResolvesToRoleAdmin() {
            storeHolds(ADMIN_ID, UserType.ADMIN);

            final UserDetails details = service.loadUserByUsername(ADMIN_ID);

            assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("the loaded principal carries the stored digest, never a plaintext password")
        void theLoadedPrincipalCarriesTheStoredDigest() {
            storeHolds(USER_ID, UserType.USER);

            final UserDetails details = service.loadUserByUsername(USER_ID);

            assertThat(details.getPassword())
                    .as("app/jcl/DUSRSECJ.jcl seeds a shared plaintext; only its digest may ever be stored")
                    .isEqualTo(SEEDED_HASH)
                    .isNotEqualTo(SYNTHETIC_PLAINTEXT)
                    .startsWith("$2a$10$");
        }

        @Test
        @DisplayName("a row with no user class fails loudly, because there is no permissive default")
        void aRowWithNoUserClassFailsLoudly() {
            final UserSecurity malformed = mock(UserSecurity.class);
            when(malformed.getPasswordHash()).thenReturn(SEEDED_HASH);
            when(malformed.getSecUsrType()).thenReturn(null);
            when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(malformed));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.loadUserByUsername(USER_ID))
                    .withMessageContaining("no permissive default");
        }
    }

    @Nested
    @DisplayName("authenticate - the sign-on comparison itself")
    class Authenticate {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("an absent identifier is refused with the identifier prompt")
        void anAbsentIdentifierIsRefused(final String blank) {
            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> service.authenticate(blank, SYNTHETIC_PLAINTEXT))
                    .withMessage("Please enter a user identifier.");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("an absent password is refused with the password prompt")
        void anAbsentPasswordIsRefused(final String blank) {
            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> service.authenticate(USER_ID, blank))
                    .withMessage("Please enter a password.");
        }

        @Test
        @DisplayName("the seeded credential is accepted")
        void theSeededCredentialIsAccepted() {
            storeHolds(USER_ID, UserType.USER);

            final UserDetails principal = service.authenticate(USER_ID, SYNTHETIC_PLAINTEXT);

            assertThat(principal.getUsername()).isEqualTo(USER_ID);
        }

        @ParameterizedTest
        @MethodSource(
                "com.cardemo.unit.security.CardDemoUserDetailsServiceTest#caseVariantsOfTheCredential")
        @DisplayName("a lower- or mixed-case password is accepted, because BOTH fields are upper-cased")
        void aLowerCasePasswordIsAccepted(final String presented) {
            storeHolds(USER_ID, UserType.USER);

            final UserDetails principal = service.authenticate("user0001", presented);

            assertThat(principal.getUsername())
                    .as("app/cbl/COSGN00C.cbl upper-cases the password as well as the identifier, so "
                            + "normalising only the identifier would reject a credential the legacy "
                            + "system accepts")
                    .isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("a wrong password is rejected")
        void aWrongPasswordIsRejected() {
            storeHolds(USER_ID, UserType.USER);

            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> service.authenticate(USER_ID, WRONG_PLAINTEXT))
                    .withMessage(REJECTION_TEXT);
        }

        @Test
        @DisplayName("an unknown user and a wrong password answer with the SAME text, defeating enumeration")
        void anUnknownUserAndAWrongPasswordAnswerAlike() {
            storeHolds(USER_ID, UserType.USER);
            when(userSecurityRepository.findById("NOSUCHID")).thenReturn(Optional.empty());

            final String wrongPasswordMessage = catchMessage(() -> service.authenticate(USER_ID, WRONG_PLAINTEXT));
            final String unknownUserMessage =
                    catchMessage(() -> service.authenticate("NOSUCHID", SYNTHETIC_PLAINTEXT));

            assertThat(unknownUserMessage)
                    .as("a caller must not be able to tell an absent row from a wrong password, or the "
                            + "sign-on endpoint becomes a user-enumeration oracle")
                    .isEqualTo(wrongPasswordMessage);
        }

        @Test
        @DisplayName("the returned principal has its credentials erased")
        void theReturnedPrincipalHasItsCredentialsErased() {
            storeHolds(USER_ID, UserType.USER);

            final UserDetails principal = service.authenticate(USER_ID, SYNTHETIC_PLAINTEXT);

            assertThat(principal.getPassword())
                    .as("the digest has served its purpose by this point and must not travel further")
                    .isNull();
        }

        @Test
        @DisplayName("a store failure is an infrastructure fault, not a credential rejection")
        void aStoreFailureIsAnInfrastructureFault() {
            when(userSecurityRepository.findById(USER_ID))
                    .thenThrow(new QueryTimeoutException("USRSEC did not answer"));

            assertThatExceptionOfType(InternalAuthenticationServiceException.class)
                    .as("telling a caller their password is wrong when the store is down is both untrue "
                            + "and unactionable")
                    .isThrownBy(() -> service.authenticate(USER_ID, SYNTHETIC_PLAINTEXT))
                    .withMessageContaining("could not be read");
        }

        @Test
        @DisplayName("an administrator authenticates and carries ROLE_ADMIN")
        void anAdministratorAuthenticates() {
            storeHolds(ADMIN_ID, UserType.ADMIN);

            final UserDetails principal = service.authenticate("admin001", SYNTHETIC_PLAINTEXT);

            assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
        }

        private static String catchMessage(final Runnable attempt) {
            try {
                attempt.run();
                throw new AssertionError("the attempt was expected to be refused");
            } catch (final BadCredentialsException | UsernameNotFoundException refused) {
                return refused.getMessage();
            }
        }
    }

    @Nested
    @DisplayName("construction guards")
    class ConstructionGuards {

        @Test
        @DisplayName("the service refuses to be built without a repository")
        void theServiceRefusesToBeBuiltWithoutARepository() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardDemoUserDetailsService(null, ENCODER))
                    .withMessageContaining("userSecurityRepository");
        }

        @Test
        @DisplayName("the service refuses to be built without an encoder")
        void theServiceRefusesToBeBuiltWithoutAnEncoder() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardDemoUserDetailsService(userSecurityRepository, null))
                    .withMessageContaining("passwordEncoder");
        }
    }
}
