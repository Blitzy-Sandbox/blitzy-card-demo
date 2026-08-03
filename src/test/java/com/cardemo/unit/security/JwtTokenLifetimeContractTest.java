/*
 * ****************************************************************************
 * Program     : JwtTokenLifetimeContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Pins the two halves of the bearer-token configuration contract
 *               that no other test owns, and that a reader cannot confirm by
 *               inspection because each half lives in a different file.
 *               ONE  - the signing key is indirected to exactly one environment
 *               variable, JWT_SIGNING_KEY, with no default, no example value and
 *               no fallback to any earlier spelling, in every one of the four
 *               profiles. A second accepted name would let a deployment start on
 *               whichever half of the pair it happened to set, which for a
 *               signing key means starting on an unintended key.
 *               TWO  - the token lifetime is expressed in WHOLE MINUTES and
 *               defaults to 30, not 3600 seconds. The lifetime of a bearer token
 *               is the replay window of a captured one, so the unit, the default
 *               and the accepted range are all asserted, together with the fact
 *               that the value reaches the expiry claim unscaled.
 * Source      : app/cbl/COSGN00C.cbl      (L98-L102 RETURN TRANSID ... COMMAREA -
 *                                          the pseudo-conversational session
 *                                          this bounded lifetime replaces)
 *               app/cpy/COCOM01Y.cpy      (L25-L28 the identity the token carries)
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.enums.UserType;
import com.cardemo.security.JwtTokenProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the published configuration contract for issued bearer tokens.
 *
 * <p>Two invariants are asserted, and both were review findings before they were tests. The signing key must
 * resolve from one environment variable with no committed default, and the token lifetime must be a bounded
 * number of minutes defaulting to 30. Each invariant spans a Java class and four YAML profiles, so each is
 * checked on both sides: the constructor behaviour in code, and the published property text on disk.
 *
 * <p>Reading the profile text rather than booting a context is deliberate. What needs proving is that no
 * profile ships a default for the key and that no profile still spells the superseded names - a claim about
 * the files themselves, which a started context cannot demonstrate because it only ever shows the one value
 * that happened to win.
 */
@DisplayName("Bearer-token configuration contract: one key variable, a lifetime in minutes")
class JwtTokenLifetimeContractTest {

    /** The one environment variable the signing key may resolve from. */
    private static final String SIGNING_KEY_VARIABLE = "JWT_SIGNING_KEY";

    /** The lifetime property, expressed in whole minutes. */
    private static final String LIFETIME_PROPERTY = "carddemo.security.jwt.expiration-minutes";

    /** The documented default lifetime, in minutes. */
    private static final long DEFAULT_LIFETIME_MINUTES = 30L;

    /** Lower bound of the accepted lifetime, in minutes. */
    private static final long MINIMUM_LIFETIME_MINUTES = 1L;

    /** Upper bound of the accepted lifetime, in minutes; 1440 minutes is 24 hours. */
    private static final long MAXIMUM_LIFETIME_MINUTES = 1_440L;

    /** Issuer claim used throughout; non-secret metadata. */
    private static final String ISSUER = "carddemo";

    /** The four profiles that make up the published configuration surface. */
    private static final List<String> PROFILES = List.of(
            "application.yml", "application-local.yml", "application-test.yml", "application-prod.yml");

    /** Superseded spellings that must not appear as live configuration in any profile. */
    private static final List<String> SUPERSEDED_TOKENS =
            List.of("signing-key: ${JWT_SECRET}", "expiration-seconds:", "JWT_EXPIRATION_SECONDS:");

    /**
     * Generates a fresh 48-byte key, comfortably above the 32-byte HS256 floor.
     *
     * @return a Base64-encoded random key, never {@code null}
     */
    private static String generatedKey() {
        final byte[] material = new byte[48];
        new SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    /**
     * Base64URL-decodes the payload segment of a compact JWS.
     *
     * <p>Decoding by hand rather than through a decoder is intentional: the assertion is about the numeric
     * claim values the issuer wrote, and a decoder would apply its own clock skew and validation before
     * handing them over.
     *
     * @param compactToken the serialised token
     * @return the payload as JSON text
     */
    private static String decodedPayload(final String compactToken) {
        final String[] segments = compactToken.split("\\.");
        assertThat(segments).as("a compact JWS has three segments").hasSize(3);
        return new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
    }

    /**
     * Extracts one numeric claim from a JSON payload without adding a parsing dependency to this tier.
     *
     * @param payload   the payload JSON
     * @param claimName the claim to read
     * @return the claim value in seconds since the epoch
     */
    private static long numericClaim(final String payload, final String claimName) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + claimName + "\"\\s*:\\s*(\\d+)")
                .matcher(payload);
        assertThat(matcher.find()).as("claim %s must be present and numeric", claimName).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /**
     * Reads one profile from the test classpath, which is the same text the runtime binds.
     *
     * @param resourceName the profile file name
     * @return the profile contents
     * @throws IOException if the resource cannot be read, which is itself a failure worth surfacing
     */
    private static String profile(final String resourceName) throws IOException {
        try (InputStream stream =
                JwtTokenLifetimeContractTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(stream).as("%s must be on the classpath", resourceName).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("The signing key resolves from one variable, with no default and no fallback")
    class SigningKeyIndirection {

        @Test
        @DisplayName("the base profile indirects the key to JWT_SIGNING_KEY and supplies no default")
        void baseProfileIndirectsWithoutDefault() throws IOException {
            final String base = profile("application.yml");

            assertThat(base)
                    .as("the key must be a bare environment reference")
                    .contains("signing-key: ${" + SIGNING_KEY_VARIABLE + "}");
            assertThat(base)
                    .as("a colon inside the placeholder would be a committed default, which is the "
                            + "hardcoded-key defect this indirection exists to close")
                    .doesNotContain("signing-key: ${" + SIGNING_KEY_VARIABLE + ":");
        }

        @Test
        @DisplayName("no profile declares the signing key twice or ships a superseded spelling")
        void noProfileShipsASupersededSpelling() throws IOException {
            for (final String name : PROFILES) {
                final String text = profile(name);
                for (final String superseded : SUPERSEDED_TOKENS) {
                    assertThat(text)
                            .as("%s must not carry the superseded configuration token %s", name, superseded)
                            .doesNotContain(superseded);
                }
            }

            // COMMENT LINES ARE EXCLUDED. Each profile carries a remediation log that documents this very
            // finding by quoting the property assignment it closed - "carddemo.security.jwt.signing-key:
            // ${JWT_SIGNING_KEY} with no default" - so a substring search over the raw text counts the
            // retraction as a second declaration and reports the documentation as the defect. What must be
            // unique is a DECLARATION, which is a line whose first non-blank character is not a comment mark.
            final long declarations = PROFILES.stream()
                    .map(name -> {
                        try {
                            return profile(name);
                        } catch (final IOException failure) {
                            throw new IllegalStateException(name, failure);
                        }
                    })
                    .filter(text -> text.lines()
                            .map(String::stripLeading)
                            .filter(line -> !line.startsWith("#"))
                            .anyMatch(line -> line.startsWith("signing-key:")))
                    .count();
            assertThat(declarations)
                    .as("exactly one profile may spell the signing key; the others inherit it")
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The lifetime is a bounded number of minutes")
    class LifetimeInMinutes {

        @Test
        @DisplayName("the base profile publishes expiration-minutes with a default of 30")
        void baseProfilePublishesThirtyMinutes() throws IOException {
            assertThat(profile("application.yml"))
                    .contains("expiration-minutes: ${JWT_EXPIRATION_MINUTES:" + DEFAULT_LIFETIME_MINUTES + "}");
        }

        @Test
        @DisplayName("the configured number is minutes, so a 30 produces a half-hour expiry claim")
        void theUnitIsMinutesAndReachesTheExpiryClaimUnscaled() {
            final Instant now = Instant.parse("2026-08-02T12:00:00Z");
            final JwtTokenProvider provider = new JwtTokenProvider(
                    generatedKey(), ISSUER, DEFAULT_LIFETIME_MINUTES, Clock.fixed(now, ZoneOffset.UTC));

            final String payload = decodedPayload(provider.issueToken("USER0001", UserType.USER));
            final long issuedAt = numericClaim(payload, "iat");
            final long expiresAt = numericClaim(payload, "exp");

            assertThat(Instant.ofEpochSecond(issuedAt)).isEqualTo(now);
            assertThat(Duration.ofSeconds(expiresAt - issuedAt))
                    .as("a lifetime of %d must be read as minutes, not seconds", DEFAULT_LIFETIME_MINUTES)
                    .isEqualTo(Duration.ofMinutes(DEFAULT_LIFETIME_MINUTES));
        }

        @ParameterizedTest
        @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, MAXIMUM_LIFETIME_MINUTES + 1, Long.MAX_VALUE})
        @DisplayName("a lifetime outside the closed range of 1 to 1440 minutes aborts construction")
        void outOfRangeLifetimeIsRefused(final long minutes) {
            assertThatThrownBy(() -> new JwtTokenProvider(generatedKey(), ISSUER, minutes, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LIFETIME_PROPERTY)
                    .hasMessageContaining("minutes");
        }

        @ParameterizedTest
        @ValueSource(longs = {MINIMUM_LIFETIME_MINUTES, DEFAULT_LIFETIME_MINUTES, MAXIMUM_LIFETIME_MINUTES})
        @DisplayName("both bounds are inclusive and the default sits inside them")
        void inRangeLifetimeIsAccepted(final long minutes) {
            assertThatCode(() -> new JwtTokenProvider(generatedKey(), ISSUER, minutes, Clock.systemUTC()))
                    .doesNotThrowAnyException();
        }
    }
}
