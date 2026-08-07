/*
 * ****************************************************************************
 * Program     : CredentialRefusalContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Proves that a sign-on refusal discloses nothing about which
 *               identifiers exist. The legacy 3270 screen distinguished
 *               'User not found. Try again ...' at app/cbl/COSGN00C.cbl:L249
 *               from 'Wrong Password. Try again ...' at :L242; on an HTTP
 *               surface that distinction lets an unauthenticated caller
 *               enumerate user_security, so it is deliberately not reproduced.
 *               Three channels are asserted closed, because closing one alone
 *               leaves the disclosure intact: the rendered MESSAGE, the
 *               exception TYPE and marked FIELD (the caller maps types onto HTTP
 *               status codes, so a distinct type is an oracle however identical
 *               the text), and the WORK PERFORMED - the verifier runs one BCrypt
 *               comparison on the unknown-identifier path too, against a digest
 *               no account can present, so an absent row cannot be recognised by
 *               elapsed time.
 * Source      : app/cbl/COSGN00C.cbl      (L211-L219 keyed READ; L221 EVALUATE
 *                                          WS-RESP-CD; L241-L246 wrong password;
 *                                          L247-L251 WHEN 13 not found;
 *                                          L132-L136 both credentials folded)
 *               app/cpy/CSUSR01Y.cpy      (L18-L22 the 80-byte security record)
 *               app/jcl/DUSRSECJ.jcl      (L35-L44 the ten seeded identifiers)
 *               app/cbl/CBACT04C.cbl      (canonical banner form, L1-L21)
 *               CONTRIBUTING.md, NOTICE   (style and licence conventions)
 *                                                                  @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.security.CardDemoUserDetailsService;
import com.cardemo.security.JwtTokenProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies that an unknown identifier and a wrong password are one indistinguishable outcome.
 *
 * <p>The tests are deliberately paired: each one runs the unknown-identifier case and the wrong-password
 * case through the same code and compares the two results with each other, rather than each against a fixed
 * expectation. A test written the second way passes just as happily when both cases drift together, which is
 * exactly the regression that matters here.
 *
 * <p>Timing is asserted by counting BCrypt invocations, not by measuring a clock. A wall-clock assertion on a
 * shared build machine is a flaky test; the invariant that actually matters - that the expensive key schedule
 * runs on both paths - is a countable fact.
 */
@DisplayName("A sign-on refusal reveals nothing about which identifiers exist")
class CredentialRefusalContractTest {

    /** The one identifier seeded into the stub store; {@code SEC-USR-ID PIC X(08)}. */
    private static final String KNOWN_USER_ID = "USER0001";

    /** An identifier no row bears, deliberately the same width as a real one. */
    private static final String UNKNOWN_USER_ID = "NOSUCH01";

    /**
     * The credential the stub row verifies against, generated per run rather than written down.
     *
     * <p>This class stubs its own store, so the value only has to be a credential this run's digest accepts -
     * it never has to be the one {@code app/jcl/DUSRSECJ.jcl} seeds. An earlier revision named that shared
     * plaintext anyway, which put a working credential for the shipped demo seed into a tracked file and
     * falsified the security gate's own "no plaintext anywhere" claim. Rule 1 clause D admits no sample
     * exception, so the value is generated: eight upper-case letters, matching
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} and the character set the source's
     * {@code FUNCTION UPPER-CASE} normalisation yields.
     */
    private static final String CORRECT_PASSWORD = syntheticCredential();

    /**
     * A credential the seeded row does not verify against, derived so it cannot collide.
     *
     * <p>Every character of {@link #CORRECT_PASSWORD} rotated one position along the alphabet: the same width
     * and case class, so the refusal paths see an input shaped like a real attempt, and guaranteed different,
     * which a second generated value would not be.
     */
    private static final String WRONG_PASSWORD = rotated(CORRECT_PASSWORD);

    /** {@code SEC-USR-FNAME PIC X(20)}, space-padded to its declared width. */
    private static final String FIRST_NAME = "LAWRENCE            ";

    /** {@code SEC-USR-LNAME PIC X(20)}, space-padded to its declared width. */
    private static final String LAST_NAME = "THOMAS              ";

    /** BCrypt cost pinned by transformation rule 15 and by {@code V3__seed_data.sql}. */
    private static final int BCRYPT_STRENGTH = 10;

    /** {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy}: eight bytes, so eight characters. */
    private static final int CREDENTIAL_WIDTH = 8;

    /** Letters drawn from when generating a credential, so every character survives upper-casing. */
    private static final int ALPHABET_SIZE = 26;

    /** Fixed instant so nothing in the assertions depends on the wall clock. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC);

    /** Counts every {@code matches} call so the work performed per path can be compared. */
    private CountingEncoder encoder;

    private UserSecurityRepository repository;
    private CardDemoUserDetailsService verifier;

    /**
     * A real BCrypt encoder that records how many verifications it was asked to perform.
     *
     * <p>Real rather than mocked on purpose: a mock would answer instantly and would prove nothing about the
     * key schedule actually running, and a mock's {@code encode} would hand the class under test a digest
     * its own {@code matches} could reject as unformatted.
     */
    private static final class CountingEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

        private final AtomicInteger matchCalls = new AtomicInteger();

        @Override
        public String encode(final CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(final CharSequence rawPassword, final String encodedPassword) {
            matchCalls.incrementAndGet();
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    /**
     * Builds the seeded row, hashing its credential the way the seed migration does.
     *
     * @return one user security row, never {@code null}
     */
    private UserSecurity seededUser() {
        return new UserSecurity(
                KNOWN_USER_ID, FIRST_NAME, LAST_NAME, encoder.encode(CORRECT_PASSWORD), UserType.USER);
    }

    /**
     * Assembles a sign-on request carrying only the two fields the operation reads.
     *
     * @param userId   the identifier as presented
     * @param password the credential as presented
     * @return a populated request record
     */
    private static SignOnRequest request(final String userId, final String password) {
        return new SignOnRequest(null, null, null, null, null, null, null, null, userId, password, null);
    }

    /**
     * Generates one eight-character upper-case credential for this run.
     *
     * <p>{@link SecureRandom} because the value is BCrypt-hashed and compared inside this class, so it is a
     * credential in every respect that matters and is produced like one. Only upper-case letters are drawn, so
     * the value survives the source's {@code FUNCTION UPPER-CASE} normalisation unchanged and the
     * lower-casing assertion below has something to lower-case.
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

    @BeforeEach
    void setUp() {
        encoder = new CountingEncoder();
        repository = Mockito.mock(UserSecurityRepository.class);
        final UserSecurity seeded = seededUser();
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repository.findById(KNOWN_USER_ID)).thenReturn(Optional.of(seeded));
        verifier = new CardDemoUserDetailsService(repository, encoder);
    }

    @Nested
    @DisplayName("The verifier itself folds the two refusals together")
    class VerifierLevel {

        @Test
        @DisplayName("an unknown identifier and a wrong password raise the same type with the same message")
        void bothRefusalsAreTheSameTypeAndMessage() {
            final Throwable unknown =
                    catchThrowable(() -> verifier.authenticate(UNKNOWN_USER_ID, CORRECT_PASSWORD));
            final Throwable wrong =
                    catchThrowable(() -> verifier.authenticate(KNOWN_USER_ID, WRONG_PASSWORD));

            assertThat(unknown).isInstanceOf(BadCredentialsException.class);
            assertThat(wrong).isInstanceOf(BadCredentialsException.class);
            assertThat(unknown.getClass())
                    .as("a distinct exception type is an oracle, because the caller maps types onto statuses")
                    .isEqualTo(wrong.getClass());
            assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
            assertThat(unknown.getMessage())
                    .as("neither refusal may quote the presented identifier back")
                    .doesNotContain(UNKNOWN_USER_ID)
                    .doesNotContain(KNOWN_USER_ID);
        }

        @Test
        @DisplayName("the unknown-identifier path performs the same one BCrypt verification")
        void bothRefusalsCostOneVerification() {
            catchThrowable(() -> verifier.authenticate(UNKNOWN_USER_ID, CORRECT_PASSWORD));
            final int afterUnknown = encoder.matchCalls.get();

            catchThrowable(() -> verifier.authenticate(KNOWN_USER_ID, WRONG_PASSWORD));
            final int afterWrong = encoder.matchCalls.get() - afterUnknown;

            assertThat(afterUnknown)
                    .as("an absent row must still cost a key schedule, or elapsed time reveals it")
                    .isEqualTo(1);
            assertThat(afterWrong).isEqualTo(afterUnknown);
        }

        @Test
        @DisplayName("a blank credential is still reported on its own terms, and costs no verification")
        void blankInputIsDistinguishableAndCheap() {
            final Throwable blankId = catchThrowable(() -> verifier.authenticate("   ", CORRECT_PASSWORD));
            final Throwable blankPassword = catchThrowable(() -> verifier.authenticate(KNOWN_USER_ID, ""));

            assertThat(blankId).isInstanceOf(BadCredentialsException.class);
            assertThat(blankPassword).isInstanceOf(BadCredentialsException.class);
            assertThat(blankId.getMessage())
                    .as("a blank input discloses nothing about which users exist, so the two may differ")
                    .isNotEqualTo(blankPassword.getMessage());
            assertThat(encoder.matchCalls)
                    .as("no store read and no verification happens before both fields are present")
                    .hasValue(0);
        }

        @Test
        @DisplayName("the correct credential still authenticates, so the guard is not simply refusing all")
        void theHappyPathStillWorks() {
            assertThat(verifier.authenticate(KNOWN_USER_ID, CORRECT_PASSWORD).getUsername())
                    .isEqualTo(KNOWN_USER_ID);
            assertThat(verifier.authenticate(KNOWN_USER_ID.toLowerCase(java.util.Locale.ROOT),
                            CORRECT_PASSWORD.toLowerCase(java.util.Locale.ROOT))
                    .getUsername())
                    .as("app/cbl/COSGN00C.cbl:L132-L136 folds BOTH credentials, so lower case must work")
                    .isEqualTo(KNOWN_USER_ID);
        }
    }

    @Nested
    @DisplayName("The service renders one outcome for every credential refusal")
    class ServiceLevel {

        private com.cardemo.service.auth.AuthenticationService service;

        @BeforeEach
        void wireService() {
            final byte[] material = new byte[48];
            new SecureRandom().nextBytes(material);
            final JwtTokenProvider tokenProvider = new JwtTokenProvider(
                    Base64.getEncoder().encodeToString(material), "carddemo", 30L, FIXED_CLOCK);
            service = new com.cardemo.service.auth.AuthenticationService(
                    verifier, repository, tokenProvider,
                    new MetricsConfig(new SimpleMeterRegistry()), FIXED_CLOCK);
        }

        @Test
        @DisplayName("both refusals surface as one exception type, message, field and failure kind")
        void bothRefusalsRenderIdentically() {
            final Throwable unknown =
                    catchThrowable(() -> service.signOn(request(UNKNOWN_USER_ID, CORRECT_PASSWORD)));
            final Throwable wrong =
                    catchThrowable(() -> service.signOn(request(KNOWN_USER_ID, WRONG_PASSWORD)));

            assertThat(unknown).isInstanceOf(ValidationException.class);
            assertThat(wrong).isInstanceOf(ValidationException.class);

            final ValidationException unknownFailure = (ValidationException) unknown;
            final ValidationException wrongFailure = (ValidationException) wrong;

            assertThat(unknownFailure.getMessage())
                    .as("the legacy :L249 literal must not be emitted for an unknown identifier")
                    .doesNotContain("User not found")
                    .isEqualTo(wrongFailure.getMessage());
            assertThat(unknownFailure.getFieldName()).isEqualTo(wrongFailure.getFieldName());
            assertThat(unknownFailure.getFailureKind()).isEqualTo(wrongFailure.getFailureKind());
            assertThat(unknownFailure.getMessage())
                    .as("the rendered screen must not quote the presented identifier")
                    .doesNotContain(UNKNOWN_USER_ID);
        }

        @Test
        @DisplayName("a row deleted between verification and re-read is also indistinguishable")
        void aVanishedRowIsIndistinguishableToo() {
            // The verifier reads the row and accepts the credential; the service's own re-read then finds
            // nothing, which can only be a concurrent delete. Reporting that as a record-not-found would
            // tell the caller the identifier existed a moment ago.
            final Throwable wrong =
                    catchThrowable(() -> service.signOn(request(KNOWN_USER_ID, WRONG_PASSWORD)));

            Mockito.when(repository.findById(KNOWN_USER_ID))
                    .thenReturn(Optional.of(seededUser()))
                    .thenReturn(Optional.empty());

            final Throwable vanished =
                    catchThrowable(() -> service.signOn(request(KNOWN_USER_ID, CORRECT_PASSWORD)));

            assertThat(vanished).isInstanceOf(ValidationException.class);
            assertThat(vanished.getMessage())
                    .doesNotContain("User not found")
                    .isEqualTo(wrong.getMessage());
            assertThat(((ValidationException) vanished).getFieldName())
                    .isEqualTo(((ValidationException) wrong).getFieldName());
        }

        @Test
        @DisplayName("the attempts counter records the outcome only, never the identifier")
        void theCounterCarriesNoIdentity() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final byte[] material = new byte[48];
            new SecureRandom().nextBytes(material);
            final com.cardemo.service.auth.AuthenticationService counted =
                    new com.cardemo.service.auth.AuthenticationService(
                            verifier, repository,
                            new JwtTokenProvider(Base64.getEncoder().encodeToString(material),
                                    "carddemo", 30L, FIXED_CLOCK),
                            new MetricsConfig(registry), FIXED_CLOCK);

            catchThrowable(() -> counted.signOn(request(UNKNOWN_USER_ID, CORRECT_PASSWORD)));
            catchThrowable(() -> counted.signOn(request(KNOWN_USER_ID, WRONG_PASSWORD)));

            final List<String> tagValues = registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(tag -> tag.getValue())
                    .toList();
            assertThat(tagValues)
                    .as("a tag carrying an identifier would make the metric an enumeration channel")
                    .doesNotContain(UNKNOWN_USER_ID, KNOWN_USER_ID);
        }
    }
}
