/*
 * ******************************************************************
 * Program     : FixedClockProviderTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the unit tier's single sanctioned time
 *               source, FixedClockProvider, against the legacy 26 byte
 *               timestamp contract: the two renderings the corpus
 *               emits, the fixed width alphanumeric move that copies a
 *               timestamp straight through, and every guard the
 *               provider raises. The provider is test support code
 *               that every deterministic timestamp assertion in this
 *               tier depends on, so an unverified guard would let a
 *               non-reproducible clock through unnoticed and turn a
 *               parity assertion elsewhere into a false green.
 * Source      : app/cbl/COBIL00C.cbl:L249-L267,
 *               app/cbl/CBACT04C.cbl:L613-L625,
 *               app/cbl/CBTRN02C.cbl:L149,L159-L175,
 *               app/cbl/COTRN02C.cbl:L464-L465,
 *               app/cpy/CSDAT01Y.cpy:L42-L55,
 *               app/cpy/CVTRA05Y.cpy:L16-L17 @ 7756d89
 * ******************************************************************
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
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link FixedClockProvider}, the only place in the unit tier permitted to decide what the
 * current instant is.
 *
 * <h2>What it does</h2>
 *
 * <p>It verifies four things and nothing else. First, that the two rendered forms are byte exact and
 * exactly {@link FixedClockProvider#TIMESTAMP_LENGTH} characters wide, matching
 * {@code TRAN-ORIG-TS PIC X(26)} and {@code TRAN-PROC-TS PIC X(26)} of
 * {@code app/cpy/CVTRA05Y.cpy:L16-L17}. Second, that the pass through move truncates and space fills
 * exactly as a COBOL {@code MOVE} into a {@code PIC X(26)} receiving field does. Third, that every guard
 * the provider declares actually fires - a null argument, a clock with no zone, a clock that is not
 * fixed, a year outside the four digit field, and a supplementary character in the sending field.
 * Fourth, that the type is shaped as a utility: final, one private constructor, no instance state.
 *
 * <p><strong>Why the guards matter enough to test.</strong> This provider is not production code; it is
 * the substrate every deterministic timestamp assertion in this tier stands on. A guard that silently
 * failed to fire would let a moving clock reach a rendering, and the resulting assertion would pass or
 * fail with the wall clock rather than with the code under test. That is the precise shape of a false
 * green, so the guards are asserted rather than assumed.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -o -B test} runs this class through {@code maven-surefire-plugin} 3.5.4, whose
 * includes are {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} and whose exclusions are
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}. This file matches the first include
 * and neither exclusion. Confirm a run through
 * {@code target/surefire-reports/com.cardemo.unit.model.FixedClockProviderTest.txt}. Where a local
 * toolchain is unavailable the pinned image reproduces it exactly:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.
 *
 * <p><strong>The concerns below are section banners rather than {@code @Nested} inner classes, and that
 * is deliberate.</strong> Measured on this toolchain with Surefire 3.5.4, grouping tests into
 * {@code @Nested} classes makes the plugin write {@code tests="0"} on the aggregate
 * {@code <testsuite>} element even though every case runs, and replaces each {@code classname} attribute
 * with the nested class's display prose so a failure can no longer be traced to a Java type. Flat
 * structure keeps the reported count true and every {@code classname} equal to this class's binary name.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>No external configuration, no fixture file, no container, no Spring context and no database. Every
 * expectation is a compile time constant in this file or a constant published by the provider itself.
 * The build contract is {@code maven-compiler-plugin} 3.14.1 at {@code release} 25 with
 * {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, all of which reach test compilation.
 *
 * <p>Mockito is on the test classpath and deliberately unused here. A {@link Clock} is trivially
 * constructible - {@link Clock#fixed(Instant, ZoneId)} and the two small subclasses declared at the foot
 * of this file cover every case - so a mock would assert the mock's behaviour instead of the provider's.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>A rendering assertion fails by four characters at the tail.</em> Symptom: the actual value
 *       ends in real fractional digits rather than zeros. Cause: the formatter was widened past the
 *       precision the source produces. Remediation: the source's own generator leaves its final four
 *       digits zero - {@code app/cbl/CBTRN02C.cbl:L159-L175} builds the value from date and time
 *       components only - so the rendering must stop at that precision. Severity: <strong>High</strong>,
 *       because every generated timestamp would differ from the legacy baseline.</li>
 *   <li><em>The moving clock guard test fails.</em> Symptom: no exception is raised. Cause: the two
 *       successive reads inside the provider returned the same instant, which happens whenever the
 *       supplied clock is coarse. Remediation: this file supplies a clock that advances by a fixed
 *       amount on every read rather than relying on {@link Clock#systemUTC()} being fine grained.
 *       Severity: <strong>Medium</strong> - the test would be flaky rather than wrong.</li>
 *   <li><em>A year boundary test fails.</em> Symptom: year 0 or year 9999 is rejected. Cause: the
 *       representable window was narrowed. Remediation: the field is a four digit year, so 0 and 9999
 *       are both representable and must be accepted; only years outside that window may be rejected.
 *       Severity: <strong>Medium</strong>.</li>
 *   <li><em>The utility shape test fails.</em> Symptom: a public constructor or an instance field is
 *       reported. Cause: state was added to a type whose entire purpose is to have none. Remediation:
 *       keep every member static and the sole constructor private. Severity: <strong>High</strong> -
 *       shared mutable state in the time source would make every dependent assertion order sensitive.
 *       </li>
 * </ul>
 */
final class FixedClockProviderTest {

    /** A zone with a non-zero offset, used to prove the zone argument is honoured rather than ignored. */
    private static final ZoneId PLUS_TWO = ZoneOffset.ofHours(2);

    /** A zone with a negative offset, used for the same purpose on the other side of UTC. */
    private static final ZoneId MINUS_FIVE = ZoneOffset.ofHours(-5);

    // =============================================================================================
    // CONCERN 1 of 6: the published constants and the utility shape.
    // =============================================================================================

    /**
     * The receiving field width is the declared width of the two timestamp fields it renders into.
     *
     * <p>{@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are both {@code PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16-L17}, so 26 is not an arbitrary buffer size: it is the field
     * contract, and both published canonical renderings must occupy it exactly.
     */
    @Test
    void timestampLengthIsTheDeclaredFieldWidthAndBothCanonicalRenderingsOccupyIt() {
        assertThat(FixedClockProvider.TIMESTAMP_LENGTH).isEqualTo(26);
        assertThat(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        assertThat(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP)
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
    }

    /**
     * The canonical zone is UTC, and the canonical instant is the moment both canonical renderings
     * describe.
     *
     * <p>The legacy fields store no offset, so a rendering is only reproducible if the zone is pinned.
     * UTC is chosen because it has no daylight rule and therefore no ambiguous or skipped local time.
     */
    @Test
    void canonicalZoneIsUtcAndTheCanonicalInstantAgreesWithBothCanonicalRenderings() {
        assertThat(FixedClockProvider.CANONICAL_ZONE).isEqualTo(ZoneOffset.UTC);
        assertThat(FixedClockProvider.CANONICAL_INSTANT).isEqualTo(Instant.parse("2022-06-10T19:27:53Z"));
        assertThat(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP).isEqualTo("2022-06-10-19.27.53.000000");
    }

    /**
     * Both canonical renderings end in four zero digits, which is the source's own precision.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L159-L175} assembles its 26 character value out of date and time
     * components alone, leaving the four low order positions zero. Reproducing that exactly is what
     * makes a generated timestamp comparable with the legacy baseline; emitting real microsecond or
     * nanosecond digits would differ on every single record.
     */
    @Test
    void bothCanonicalRenderingsEndInTheSourcesFourZeroDigits() {
        assertThat(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP).endsWith("0000");
        assertThat(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP).endsWith("0000");
    }

    /**
     * The type is a utility: final, no instance state, and one private constructor.
     *
     * <p>A time source with instance state would be shared mutable state across every test that used
     * it, which is exactly the hazard this class was introduced to remove.
     */
    @Test
    void theProviderIsAFinalUtilityTypeWithNoInstanceStateAndOnePrivateConstructor() {

        assertThat(Modifier.isFinal(FixedClockProvider.class.getModifiers())).isTrue();

        Constructor<?>[] constructors = FixedClockProvider.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
        assertThat(constructors[0].getParameterCount()).isZero();

        for (Field declared : FixedClockProvider.class.getDeclaredFields()) {
            assertThat(Modifier.isStatic(declared.getModifiers()))
                    .as("field %s must be static", declared.getName())
                    .isTrue();
            assertThat(Modifier.isFinal(declared.getModifiers()))
                    .as("field %s must be final", declared.getName())
                    .isTrue();
        }

        for (Method declared : FixedClockProvider.class.getDeclaredMethods()) {
            assertThat(Modifier.isStatic(declared.getModifiers()))
                    .as("method %s must be static", declared.getName())
                    .isTrue();
        }
    }

    /**
     * The published operation set is exactly the six the tier needs, so no additional time source can be
     * introduced here without the census failing.
     */
    @Test
    void theProviderPublishesExactlyTheSixSanctionedOperations() {

        String[] published = Arrays.stream(FixedClockProvider.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .distinct()
                .sorted()
                .toArray(String[]::new);

        assertThat(published).containsExactly("batchTimestamp", "canonicalClock", "fixedClock",
                "onlineTimestamp", "passThroughTimestamp");
    }

    // =============================================================================================
    // CONCERN 2 of 6: the clock factories.
    // =============================================================================================

    /**
     * The canonical clock is pinned to the canonical instant in the canonical zone, and is genuinely
     * fixed: two successive reads return the same instant.
     */
    @Test
    void canonicalClockIsPinnedToTheCanonicalInstantInTheCanonicalZoneAndDoesNotAdvance() {

        Clock clock = FixedClockProvider.canonicalClock();

        assertThat(clock.getZone()).isEqualTo(FixedClockProvider.CANONICAL_ZONE);
        assertThat(clock.instant()).isEqualTo(FixedClockProvider.CANONICAL_INSTANT);
        assertThat(clock.instant()).isEqualTo(clock.instant());
    }

    /**
     * Two separately obtained canonical clocks are interchangeable, which is what lets independent tests
     * share the constant without coordinating.
     */
    @Test
    void twoCanonicalClocksRenderIdenticallyAndAreThereforeInterchangeable() {

        Clock first = FixedClockProvider.canonicalClock();
        Clock second = FixedClockProvider.canonicalClock();

        assertThat(FixedClockProvider.onlineTimestamp(first))
                .isEqualTo(FixedClockProvider.onlineTimestamp(second));
        assertThat(FixedClockProvider.batchTimestamp(first))
                .isEqualTo(FixedClockProvider.batchTimestamp(second));
    }

    /**
     * The single argument factory defaults the zone to the canonical one rather than to the platform
     * default, so a rendering cannot vary with the host's configuration.
     */
    @Test
    void singleArgumentFixedClockDefaultsToTheCanonicalZoneAndNotThePlatformDefault() {

        Clock clock = FixedClockProvider.fixedClock(FixedClockProvider.CANONICAL_INSTANT);

        assertThat(clock.getZone()).isEqualTo(FixedClockProvider.CANONICAL_ZONE);
        assertThat(FixedClockProvider.onlineTimestamp(clock))
                .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
    }

    /**
     * The two argument factory honours the supplied zone, which is proved by rendering the same instant
     * in three zones and getting three different local times.
     */
    @Test
    void twoArgumentFixedClockHonoursTheSuppliedZone() {

        Instant moment = FixedClockProvider.CANONICAL_INSTANT;

        String utc = FixedClockProvider.onlineTimestamp(
                FixedClockProvider.fixedClock(moment, ZoneOffset.UTC));
        String plusTwo = FixedClockProvider.onlineTimestamp(
                FixedClockProvider.fixedClock(moment, PLUS_TWO));
        String minusFive = FixedClockProvider.onlineTimestamp(
                FixedClockProvider.fixedClock(moment, MINUS_FIVE));

        assertThat(utc).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(plusTwo).isEqualTo("2022-06-10 21:27:53.000000");
        assertThat(minusFive).isEqualTo("2022-06-10 14:27:53.000000");
        assertThat(utc).isNotEqualTo(plusTwo).isNotEqualTo(minusFive);
    }

    /**
     * A null instant is rejected rather than defaulted, because defaulting it would reintroduce the
     * ambient clock the provider exists to remove.
     */
    @Test
    void aNullInstantIsRejectedByBothFactoryOverloads() {

        assertThatNullPointerException()
                .isThrownBy(() -> FixedClockProvider.fixedClock(null))
                .withMessageContaining("instant must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> FixedClockProvider.fixedClock(null, ZoneOffset.UTC))
                .withMessageContaining("instant must not be null");
    }

    /**
     * A null zone is rejected rather than defaulted, for the same reason: the legacy fields store no
     * offset, so the zone has to be explicit.
     */
    @Test
    void aNullZoneIsRejectedRatherThanDefaulted() {
        assertThatNullPointerException()
                .isThrownBy(() -> FixedClockProvider.fixedClock(FixedClockProvider.CANONICAL_INSTANT, null))
                .withMessageContaining("zone must not be null");
    }

    // =============================================================================================
    // CONCERN 3 of 6: the two renderings.
    // =============================================================================================

    /**
     * The online rendering is the space separated, colon delimited form with a six digit fraction, and it
     * occupies the declared width exactly.
     */
    @Test
    void onlineTimestampRendersTheSpaceSeparatedFormAtTheDeclaredWidth() {

        String rendered = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());

        assertThat(rendered).isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        assertThat(rendered).hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        assertThat(rendered.charAt(10)).isEqualTo(' ');
        assertThat(rendered.charAt(13)).isEqualTo(':');
        assertThat(rendered.charAt(16)).isEqualTo(':');
        assertThat(rendered.charAt(19)).isEqualTo('.');
    }

    /**
     * The batch rendering is the wholly hyphen and full stop delimited form, distinct from the online one
     * at three separator positions, and it too occupies the declared width exactly.
     */
    @Test
    void batchTimestampRendersTheHyphenatedFormAtTheDeclaredWidth() {

        String rendered = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());

        assertThat(rendered).isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);
        assertThat(rendered).hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        assertThat(rendered.charAt(10)).isEqualTo('-');
        assertThat(rendered.charAt(13)).isEqualTo('.');
        assertThat(rendered.charAt(16)).isEqualTo('.');
        assertThat(rendered.charAt(19)).isEqualTo('.');
        assertThat(rendered).isNotEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
    }

    /**
     * Both renderings zero pad every component, so a single digit month, day, hour, minute or second
     * still occupies its declared two positions and the value stays 26 characters wide.
     */
    @Test
    void bothRenderingsZeroPadEveryComponentSoTheWidthNeverVaries() {

        Clock clock = FixedClockProvider.fixedClock(
                ZonedDateTime.of(2001, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant());

        assertThat(FixedClockProvider.onlineTimestamp(clock)).isEqualTo("2001-02-03 04:05:06.000000");
        assertThat(FixedClockProvider.batchTimestamp(clock)).isEqualTo("2001-02-03-04.05.06.000000");
    }

    /**
     * Sub-second precision below the source's is discarded rather than rendered, because the source's own
     * generator has no access to it and its four low order positions are always zero.
     */
    @Test
    void subSecondPrecisionIsDiscardedRatherThanRendered() {

        Clock clock = FixedClockProvider.fixedClock(
                FixedClockProvider.CANONICAL_INSTANT.plusNanos(123_456_789L));

        assertThat(FixedClockProvider.onlineTimestamp(clock))
                .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        assertThat(FixedClockProvider.batchTimestamp(clock))
                .endsWith("0000")
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
    }

    /**
     * Both renderings are pure functions of the clock: repeated calls return equal values, which is the
     * determinism guarantee the tier depends on.
     */
    @Test
    void bothRenderingsAreDeterministicAcrossRepeatedCalls() {

        Clock clock = FixedClockProvider.canonicalClock();

        for (int repetition = 0; repetition < 5; repetition++) {
            assertThat(FixedClockProvider.onlineTimestamp(clock))
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(FixedClockProvider.batchTimestamp(clock))
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);
        }
    }

    // =============================================================================================
    // CONCERN 4 of 6: the clock guards.
    // =============================================================================================

    /**
     * A null clock is rejected by both renderings, naming the two factories the caller should have used.
     */
    @Test
    void aNullClockIsRejectedByBothRenderings() {

        assertThatNullPointerException()
                .isThrownBy(() -> FixedClockProvider.onlineTimestamp(null))
                .withMessageContaining("clock must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> FixedClockProvider.batchTimestamp(null))
                .withMessageContaining("clock must not be null");
    }

    /**
     * A clock whose zone is null is rejected, because without a zone an instant cannot be resolved to the
     * local date and time the 26 character field stores.
     *
     * <p>{@link Clock} is an abstract class rather than a sealed one, so a caller can supply an
     * implementation that breaks its own documented contract. The guard exists for exactly that case and
     * is reached here through {@link ZonelessClock}.
     */
    @Test
    void aClockWithoutAZoneIsRejected() {

        Clock zoneless = new ZonelessClock(FixedClockProvider.CANONICAL_INSTANT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.onlineTimestamp(zoneless))
                .withMessageContaining("clock.getZone() returned null");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.batchTimestamp(zoneless))
                .withMessageContaining("clock.getZone() returned null");
    }

    /**
     * A clock that advances between reads is rejected, because any rendering of it would be
     * irreproducible - which is the whole failure mode this provider was introduced to prevent.
     *
     * <p>The advancing clock is supplied explicitly rather than by reaching for a system clock: a system
     * clock may legitimately return the same instant twice on a coarse platform, which would make the
     * assertion flaky rather than wrong.
     */
    @Test
    void aClockThatAdvancesBetweenReadsIsRejected() {

        Clock moving = new AdvancingClock(FixedClockProvider.CANONICAL_INSTANT, Duration.ofSeconds(1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.onlineTimestamp(moving))
                .withMessageContaining("clock is not fixed");

        Clock movingAgain = new AdvancingClock(FixedClockProvider.CANONICAL_INSTANT, Duration.ofNanos(1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.batchTimestamp(movingAgain))
                .withMessageContaining("clock is not fixed");
    }

    /**
     * Both ends of the four digit year window are accepted, because both are representable in the field
     * the source declares.
      *
      * @param year the year under test
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1582, 2022, 9998, 9999})
    void everyYearInsideTheFourDigitWindowIsAccepted(final int year) {

        Clock clock = FixedClockProvider.fixedClock(
                ZonedDateTime.of(year, 6, 10, 19, 27, 53, 0, ZoneOffset.UTC).toInstant());

        assertThat(FixedClockProvider.onlineTimestamp(clock))
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                .startsWith(String.format(java.util.Locale.ROOT, "%04d", year));
        assertThat(FixedClockProvider.batchTimestamp(clock))
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
    }

    /**
     * A year outside the four digit window is rejected, because the rendering would no longer be 26
     * characters wide and the receiving field cannot hold it.
      *
      * @param year the year under test
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, -1000, 10_000, 12_345})
    void aYearOutsideTheFourDigitWindowIsRejected(final int year) {

        Clock clock = FixedClockProvider.fixedClock(
                ZonedDateTime.of(year, 6, 10, 19, 27, 53, 0, ZoneOffset.UTC).toInstant());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.onlineTimestamp(clock))
                .withMessageContaining("cannot be represented");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.batchTimestamp(clock))
                .withMessageContaining("cannot be represented");
    }

    /**
     * The year window is evaluated in the clock's own zone, not in UTC, so an instant one hour either
     * side of a boundary is judged on the local year it renders as.
     *
     * <p>The instant chosen is the last second of year 9999 in UTC. Rendered in UTC it is inside the
     * window; rendered two hours east it falls into year 10000 and is rejected. The guard therefore has
     * to read the zoned year rather than the instant's UTC year, and this asserts that it does.
     */
    @Test
    void theYearWindowIsEvaluatedInTheClocksOwnZone() {

        Instant lastSecondOf9999 =
                ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant();

        assertThat(FixedClockProvider.onlineTimestamp(
                FixedClockProvider.fixedClock(lastSecondOf9999, ZoneOffset.UTC)))
                .isEqualTo("9999-12-31 23:59:59.000000");

        Clock shiftedEast = FixedClockProvider.fixedClock(lastSecondOf9999, PLUS_TWO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.onlineTimestamp(shiftedEast))
                .withMessageContaining("cannot be represented");
    }

    // =============================================================================================
    // CONCERN 5 of 6: the fixed width pass through move.
    // =============================================================================================

    /**
     * A sender shorter than the receiving field is space filled on the right, exactly as a COBOL
     * alphanumeric {@code MOVE} into a {@code PIC X(26)} field does.
     *
     * @param sender the sending field content to be moved
     * @param senderLength the declared width of that sending field
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "\"\"                           | 0",
        "\"A\"                          | 1",
        "\"2022-06-10\"                 | 10",
        "\"2022-06-10-19.27.53.00000\"  | 25",
    })
    void aSenderShorterThanTheReceivingFieldIsSpaceFilledOnTheRight(final String sender,
            final int senderLength) {

        String moved = FixedClockProvider.passThroughTimestamp(sender);

        assertThat(sender).hasSize(senderLength);
        assertThat(moved).hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        assertThat(moved).startsWith(sender);
        assertThat(moved.substring(senderLength))
                .isEqualTo(" ".repeat(FixedClockProvider.TIMESTAMP_LENGTH - senderLength));
    }

    /**
     * A sender of exactly the receiving width is copied through unchanged, neither padded nor truncated.
     */
    @Test
    void aSenderOfExactlyTheReceivingWidthIsCopiedThroughUnchanged() {

        assertThat(FixedClockProvider.passThroughTimestamp(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP))
                .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);
        assertThat(FixedClockProvider.passThroughTimestamp(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP))
                .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
    }

    /**
     * A sender longer than the receiving field is truncated on the right, which is what a COBOL
     * alphanumeric {@code MOVE} does when the sender overflows the receiver.
     */
    @Test
    void aSenderLongerThanTheReceivingFieldIsTruncatedOnTheRight() {

        String overlong = FixedClockProvider.CANONICAL_BATCH_TIMESTAMP + "XYZ";

        assertThat(FixedClockProvider.passThroughTimestamp(overlong))
                .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP)
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);

        assertThat(FixedClockProvider.passThroughTimestamp("X".repeat(200)))
                .isEqualTo("X".repeat(FixedClockProvider.TIMESTAMP_LENGTH));
    }

    /**
     * The move always yields exactly the declared width, whatever the sender's length, because that is
     * the one guarantee a fixed width receiving field makes.
      *
      * @param senderLength the length of the sending field
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 25, 26, 27, 100})
    void theMoveAlwaysYieldsExactlyTheDeclaredWidth(final int senderLength) {
        assertThat(FixedClockProvider.passThroughTimestamp("9".repeat(senderLength)))
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
    }

    /**
     * A null sender is rejected rather than read as blank, because a COBOL alphanumeric move has no null
     * sender: a null here means a value went missing upstream, and silently substituting spaces would
     * hide that.
     */
    @Test
    void aNullSenderIsRejectedRatherThanReadAsBlank() {
        assertThatNullPointerException()
                .isThrownBy(() -> FixedClockProvider.passThroughTimestamp(null))
                .withMessageContaining("sendingField must not be null");
    }

    /**
     * A sender carrying a supplementary character is rejected, because such a character occupies two
     * {@code char} positions but one field position and would break the fixed width contract.
     */
    @Test
    void aSenderCarryingASupplementaryCharacterIsRejected() {

        String withSupplementary = "2022-06-10-19.27.5" + new String(Character.toChars(0x1F600));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.passThroughTimestamp(withSupplementary))
                .withMessageContaining("supplementary character");
    }

    /**
     * The supplementary character guard is evaluated before truncation, so an overlong sender carrying
     * one is still rejected rather than silently trimmed back to a well formed prefix.
     */
    @Test
    void theSupplementaryCharacterGuardIsEvaluatedBeforeTruncation() {

        String overlongWithSupplementary =
                FixedClockProvider.CANONICAL_BATCH_TIMESTAMP + new String(Character.toChars(0x1F600));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixedClockProvider.passThroughTimestamp(overlongWithSupplementary))
                .withMessageContaining("supplementary character");
    }

    /**
     * A lone surrogate is not a supplementary character and is therefore not rejected by that guard: the
     * count of code points equals the count of {@code char} positions, so the width contract still holds.
     *
     * <p>This is asserted so that the guard's boundary is documented rather than assumed. It is a
     * deliberate consequence of counting code points, not an oversight.
     */
    @Test
    void aLoneSurrogateIsNotTreatedAsASupplementaryCharacter() {
        assertThat(FixedClockProvider.passThroughTimestamp("\uD83D"))
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                .startsWith("\uD83D");
    }

    // =============================================================================================
    // CONCERN 6 of 6: the two clock implementations this file supplies, and nothing else.
    // =============================================================================================

    /**
     * A clock that reports no zone, used to reach the provider's zone guard.
     *
     * <p>Returning null from {@link Clock#getZone()} breaks {@link Clock}'s own contract, which is
     * precisely why the provider guards against it: {@link Clock} is an abstract class, so nothing stops
     * a caller supplying such an implementation.
     */
    private static final class ZonelessClock extends Clock {

        private final Instant instant;

        private ZonelessClock(final Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return null;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }

    /**
     * A clock that advances by a fixed amount on every read, used to reach the provider's not-fixed
     * guard deterministically.
     *
     * <p>The state is confined to one instance created inside a single test method and is never shared,
     * so it is not shared mutable state.
     */
    private static final class AdvancingClock extends Clock {

        private final Duration step;

        private Instant current;

        private AdvancingClock(final Instant start, final Duration step) {
            this.current = start;
            this.step = step;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant read = this.current;
            this.current = this.current.plus(this.step);
            return read;
        }
    }
}
