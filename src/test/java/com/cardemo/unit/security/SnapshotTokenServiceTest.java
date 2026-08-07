/*
 * ****************************************************************************
 * Program     : SnapshotTokenServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies SnapshotTokenService: the sealed round trip, refusal of
 *               a tampered, foreign, misdirected or expired token, opacity of the
 *               wire value, and fail-fast configuration validation.
 * Source      : app/cbl/COCRDLIC.cbl:L237 (WS-CA-SCREEN-NUM, the page
 *                 number the COMMAREA carried)
 *               + app/cbl/COCRDLIC.cbl:L1197-L1205 (saved first and last card
 *                 number of the displayed page)
 *               @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.security.SnapshotTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@code com.cardemo.security.SnapshotTokenService}.
 *
 * <p>The component exists because the legacy conversation carried its browse state in the COMMAREA - the
 * page number and the saved first and last keys of the page on display - and a stateless REST target has no
 * such carrier. Handing those keys over in the clear would disclose a card number and would put the browse
 * position under the caller's control, and a bare echo would be replayable. These tests assert that none of
 * those three is possible: the payload is recoverable only by the server, only for the operation and the
 * record it was issued for, and only inside its lifetime. The as-displayed snapshot for the change
 * comparison is <em>not</em> carried here - it travels in the request body as {@code oldDetails}.</p>
 */
@DisplayName("SnapshotTokenService: browse cursors and row references as authenticated opaque tokens")
class SnapshotTokenServiceTest {

    /** A key of at least the 32 bytes the component requires, generated per run so nothing is committed. */
    private static final String KEY = ephemeralSigningKey();

    /**
     * A second, unrelated key, used to prove a token sealed under one key does not open under another.
     *
     * <p>Generated in a loop that rejects a value equal to {@link #KEY}. The probability of a collision
     * between two 256-bit draws is negligible, but "negligible" is not "impossible", and a test whose whole
     * point is that two keys differ must not rest on a probability.
     */
    private static final String OTHER_KEY = ephemeralSigningKeyOtherThan(KEY);

    /** Fifteen minutes, the component's documented default. */
    private static final long LIFETIME_SECONDS = 900L;

    /** A fixed instant, so expiry is asserted by moving the clock rather than by sleeping. */
    private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");

    /** The operation kind used throughout. */
    private static final String KIND = "account-update";

    /** The record key used throughout. */
    private static final String RECORD = "00000000011";

    /** Builds a service over a fixed clock. */
    private static SnapshotTokenService serviceAt(final String key, final Instant instant) {
        return new SnapshotTokenService(key, LIFETIME_SECONDS,
                Clock.fixed(instant, ZoneOffset.UTC), new ObjectMapper());
    }

    /** The default service: the standard key at the standard instant. */
    private static SnapshotTokenService service() {
        return serviceAt(KEY, NOW);
    }

    /**
     * A minimal snapshot shape. It carries a value that must never be disclosed so that opacity can be
     * asserted on something recognisable.
     *
     * @param accountId the record key echoed inside the payload
     * @param socialSecurityNumber a protected value; asserted absent from the wire form
     */
    record Snapshot(String accountId, String socialSecurityNumber) {
    }

    @Nested
    @DisplayName("The round trip")
    class RoundTrip {

        @Test
        @DisplayName("A sealed payload is recovered exactly, component for component")
        void sealedPayloadIsRecoveredExactly() {
            final SnapshotTokenService service = service();
            final Snapshot original = new Snapshot(RECORD, "123456789");

            final String token = service.seal(KIND, RECORD, original);
            final Snapshot recovered = service.open(token, KIND, RECORD, Snapshot.class);

            assertThat(recovered).isEqualTo(original);
        }

        @Test
        @DisplayName("The wire value discloses neither the payload nor the protected value inside it")
        void wireValueIsOpaque() {
            final String token = service().seal(KIND, RECORD, new Snapshot(RECORD, "123456789"));

            assertThat(token)
                    .as("a protected value must not survive into the token in any recoverable form")
                    .doesNotContain("123456789")
                    .doesNotContain(RECORD)
                    .doesNotContain("socialSecurityNumber");
            assertThat(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8))
                    .as("nor into its decoded bytes, which are ciphertext rather than JSON")
                    .doesNotContain("123456789")
                    .doesNotContain("accountId");
        }

        @Test
        @DisplayName("Two seals of the same payload differ, because each draws a fresh nonce")
        void sealsAreNotDeterministic() {
            final SnapshotTokenService service = service();
            final Snapshot payload = new Snapshot(RECORD, "123456789");

            assertThat(service.seal(KIND, RECORD, payload))
                    .isNotEqualTo(service.seal(KIND, RECORD, payload));
        }

        @Test
        @DisplayName("The token is URL and header safe: base64url, unpadded")
        void tokenIsUrlSafe() {
            assertThat(service().seal(KIND, RECORD, new Snapshot(RECORD, "1")))
                    .matches("[A-Za-z0-9_-]+");
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("An absent token is reported as unconfirmed, because the remedy is to obtain one")
        void absentTokenIsUnconfirmed() {
            final SnapshotTokenService service = service();

            for (final String absent : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> service.open(absent, KIND, RECORD, Snapshot.class))
                        .isInstanceOf(ConcurrentUpdateException.class)
                        .hasMessage(SnapshotTokenService.MISSING_TOKEN_MESSAGE)
                        .extracting(failure -> ((ConcurrentUpdateException) failure).getOutcome())
                        .isEqualTo(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED);
            }
        }

        @Test
        @DisplayName("An edited token is refused, and the refusal names no reason")
        void tamperedTokenIsRefused() {
            final SnapshotTokenService service = service();
            final String token = service.seal(KIND, RECORD, new Snapshot(RECORD, "123456789"));
            final char[] characters = token.toCharArray();
            characters[characters.length - 1] = characters[characters.length - 1] == 'A' ? 'B' : 'A';
            final String edited = new String(characters);

            assertThatThrownBy(() -> service.open(edited, KIND, RECORD, Snapshot.class))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("A truncated token is refused")
        void truncatedTokenIsRefused() {
            final SnapshotTokenService service = service();
            final String token = service.seal(KIND, RECORD, new Snapshot(RECORD, "1"));
            final String truncated = token.substring(0, 8);

            assertThatThrownBy(() -> service.open(truncated, KIND, RECORD, Snapshot.class))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("A value that is not base64url at all is refused rather than propagating a decode error")
        void nonBase64TokenIsRefused() {
            final SnapshotTokenService service = service();

            assertThatThrownBy(() -> service.open("not a token!!", KIND, RECORD, Snapshot.class))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("A token issued for another record does not open for this one")
        void tokenIsBoundToItsRecord() {
            final SnapshotTokenService service = service();
            final String token = service.seal(KIND, RECORD, new Snapshot(RECORD, "1"));

            assertThatThrownBy(() -> service.open(token, KIND, "00000000022", Snapshot.class))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("A token issued for another operation does not open for this one")
        void tokenIsBoundToItsKind() {
            final SnapshotTokenService service = service();
            final String token = service.seal(KIND, RECORD, new Snapshot(RECORD, "1"));

            assertThatThrownBy(() -> service.open(token, "card-update", RECORD, Snapshot.class))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("A token sealed under a different key does not open")
        void tokenIsBoundToItsKey() {
            final String token = serviceAt(OTHER_KEY, NOW).seal(KIND, RECORD, new Snapshot(RECORD, "1"));
            final SnapshotTokenService service = service();

            assertThatThrownBy(() -> service.open(token, KIND, RECORD, Snapshot.class))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("A token is refused once the clock has passed its expiry, and accepted at it")
        void tokenExpires() {
            final String token = service().seal(KIND, RECORD, new Snapshot(RECORD, "1"));

            final SnapshotTokenService atExpiry =
                    serviceAt(KEY, NOW.plusSeconds(LIFETIME_SECONDS));
            assertThat(atExpiry.open(token, KIND, RECORD, Snapshot.class).accountId())
                    .as("the boundary resolves in the caller's favour")
                    .isEqualTo(RECORD);

            final SnapshotTokenService afterExpiry =
                    serviceAt(KEY, NOW.plusSeconds(LIFETIME_SECONDS + 1L));
            assertThatThrownBy(() -> afterExpiry.open(token, KIND, RECORD, Snapshot.class))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("An authenticated payload of the wrong shape is an abend, not a caller error")
        void wrongPayloadShapeIsAnAbend() {
            final SnapshotTokenService service = service();
            final String token = service.seal(KIND, RECORD, new Snapshot(RECORD, "1"));

            assertThatThrownBy(() -> service.open(token, KIND, RECORD, Integer.class))
                    .isInstanceOf(FatalProcessingException.class)
                    .extracting(failure -> ((FatalProcessingException) failure).getAbendCode())
                    .isEqualTo("0999");
        }

        @Test
        @DisplayName("A blank kind or record key is a programming error, reported as such")
        void blankBindingArgumentsAreRejected() {
            final SnapshotTokenService service = service();
            final Snapshot payload = new Snapshot(RECORD, "1");

            assertThatThrownBy(() -> service.seal(" ", RECORD, payload))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("kind");
            assertThatThrownBy(() -> service.seal(KIND, null, payload))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recordKey");
            assertThatThrownBy(() -> service.seal(KIND, RECORD, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Browse cursors")
    class Cursors {

        @Test
        @DisplayName("A card number survives the round trip without ever appearing on the wire")
        void cursorRoundTripsOpaquely() {
            final SnapshotTokenService service = service();
            final String cardNumber = "4111111111111111";

            final String sealed = service.sealCursor("card-list", cardNumber);

            assertThat(sealed).doesNotContain(cardNumber);
            assertThat(service.openCursor("card-list", sealed)).isEqualTo(cardNumber);
        }

        @Test
        @DisplayName("An absent cursor stays absent in both directions: a first request is not a failure")
        void absentCursorIsNotAFailure() {
            final SnapshotTokenService service = service();

            assertThat(service.sealCursor("card-list", null)).isNull();
            assertThat(service.openCursor("card-list", null)).isNull();
            assertThat(service.openCursor("card-list", "  ")).isNull();
        }

        @Test
        @DisplayName("A cursor sealed for one browse does not open for another")
        void cursorIsBoundToItsBrowse() {
            final SnapshotTokenService service = service();
            final String sealed = service.sealCursor("card-list", "4111111111111111");

            assertThatThrownBy(() -> service.openCursor("transaction-list", sealed))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        }

        @Test
        @DisplayName("An empty cursor value is sealable and distinguishable from an absent one")
        void emptyCursorIsPreserved() {
            final SnapshotTokenService service = service();

            final String sealed = service.sealCursor("card-list", "");

            assertThat(sealed).isNotNull();
            assertThat(service.openCursor("card-list", sealed)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Configuration is validated at construction, not at first use")
    class Configuration {

        @Test
        @DisplayName("An absent or blank key aborts construction and names the variable to export")
        void blankKeyFailsFast() {
            final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            final ObjectMapper mapper = new ObjectMapper();

            for (final String blank : new String[] {null, "", "   "}) {
                assertThatThrownBy(
                        () -> new SnapshotTokenService(blank, LIFETIME_SECONDS, clock, mapper))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("JWT_SIGNING_KEY");
            }
        }

        @Test
        @DisplayName("A short key aborts construction and never reproduces the configured value")
        void shortKeyFailsFast() {
            final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

            assertThatThrownBy(() -> new SnapshotTokenService("too-short", LIFETIME_SECONDS, clock,
                    new ObjectMapper()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32")
                    .hasMessageNotContaining("too-short");
        }

        @Test
        @DisplayName("A non-positive lifetime aborts construction")
        void nonPositiveLifetimeFailsFast() {
            final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            final ObjectMapper mapper = new ObjectMapper();

            assertThatThrownBy(() -> new SnapshotTokenService(KEY, 0L, clock, mapper))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lifetime-seconds");
            assertThatThrownBy(() -> new SnapshotTokenService(KEY, -1L, clock, mapper))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("A missing collaborator aborts construction")
        void missingCollaboratorFailsFast() {
            final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

            assertThatThrownBy(
                    () -> new SnapshotTokenService(KEY, LIFETIME_SECONDS, null, new ObjectMapper()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("clock");
            assertThatThrownBy(() -> new SnapshotTokenService(KEY, LIFETIME_SECONDS, clock, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("objectMapper");
        }
    }

    /**
     * Generates a single-use signing key for this suite.
     *
     * <p>Rule 1 Clause D forbids secrets in code, in configuration and <em>in tests</em>, with no carve-out
     * for material that happens to be synthetic: a literal key in a committed file is still committed key
     * material, indexable and copyable into a deployment, and it teaches the pattern the clause exists to
     * stop. Generating it removes the class of problem instead of declaring one instance of it harmless. The
     * value exists only in memory for the lifetime of this class, and no assertion depends on its content -
     * only on its being long enough, internally consistent, and distinct from {@link #OTHER_KEY}.
     *
     * <p>Thirty-two bytes of entropy is the HS256 minimum the component enforces; URL-safe unpadded encoding
     * widens that to forty-three characters, so the length guard passes with room to spare.
     *
     * @return a freshly generated key, never {@code null}, never logged and never persisted
     */
    private static String ephemeralSigningKey() {
        final byte[] keyMaterial = new byte[32];
        new SecureRandom().nextBytes(keyMaterial);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyMaterial);
    }

    /**
     * Generates a key that is guaranteed to differ from the one supplied.
     *
     * <p>The loop is what makes "two different keys" a fact rather than a near-certainty. It cannot spin: each
     * draw is independent and the rejected value is a single point in a 256-bit space.
     *
     * @param excluded the key the result must not equal; must not be {@code null}
     * @return a freshly generated key that is not equal to {@code excluded}
     */
    private static String ephemeralSigningKeyOtherThan(final String excluded) {
        String candidate = ephemeralSigningKey();
        while (candidate.equals(excluded)) {
            candidate = ephemeralSigningKey();
        }
        return candidate;
    }

}
