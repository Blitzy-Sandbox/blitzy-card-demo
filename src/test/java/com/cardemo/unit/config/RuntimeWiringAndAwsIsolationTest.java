/*
 * ****************************************************************************
 * Program     : RuntimeWiringAndAwsIsolationTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies the three runtime-wiring and cloud-isolation contracts
 *               that decide whether this application can start at all and
 *               whether it can reach live cloud infrastructure: the single
 *               java.time.Clock bean every time-dependent singleton is built
 *               with, the emulator endpoint allow list AwsConfig enforces
 *               before any other validation, and the single exact anonymous
 *               sign-on route.
 * Source      : app/csd/CARDDEMO.CSD:L378 (DEFINE TRANSACTION(CC00) -> COSGN00C,
 *                 the only unauthenticated transaction)
 *               + app/cbl/COSGN00C.cbl:L37 (WS-TRANID PIC X(04) VALUE 'CC00')
 *               + app/cbl/CBTRN02C.cbl:L678-L706 (the PIC X(26) generated time
 *                 stamp whose zone the application clock fixes)
 *               + app/csd/CARDDEMO.CSD:L499-L503 (DEFINE TDQUEUE(JOBS), the
 *                 queue whose endpoint is validated here)
 *               + app/jcl/DEFGDGB.jcl (the seven GDG bases the buckets replace)
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
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.config.AwsConfig;
import com.cardemo.config.ObservabilityConfig;
import com.cardemo.config.SecurityConfig;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the three contracts that decide whether the application object graph can be created and
 * whether it is capable of reaching live cloud infrastructure.
 *
 * <p><strong>What it does.</strong> Three nested groups, one per contract:
 *
 * <ol>
 *   <li>{@link ApplicationClockContract} - the single {@link Clock} bean. Thirteen singleton constructors
 *       take a {@code Clock}, all of them during context refresh, so a missing bean is not a latent defect
 *       but a context that cannot be created. The tests assert the bean exists, that it ticks in UTC by
 *       default, that a deployment can pin a different zone, and that a zone identifier the runtime does not
 *       recognise aborts startup with the value named.</li>
 *   <li>{@link CloudEndpointAllowListContract} - the emulator allow list. The migration plan permits no code
 *       path to reach a live cloud service, and an omitted endpoint override is not a refusal to call one but
 *       a silent election to, because the SDK derives a real regional endpoint from the region. The tests
 *       drive the constructor with accepted and refused endpoints and assert that refusal aborts
 *       construction.</li>
 *   <li>{@link AnonymousSignOnRouteContract} - the one anonymous route. {@code app/csd/CARDDEMO.CSD} defines
 *       exactly one unauthenticated transaction, so exactly one path may be permitted anonymously; the tests
 *       assert the configured path is a concrete route rather than a prefix pattern.</li>
 *   </ol>
 *
 * <p><strong>How to run.</strong> {@code ./mvnw -B -ntp -o test -Dtest=RuntimeWiringAndAwsIsolationTest}.
 * No container, no database, no network and no environment variable is required: every subject is
 * constructed directly with explicit arguments, which is the whole reason the collaborators are injected.
 *
 * <p><strong>Key configuration.</strong> None is read. The property keys under test are private to their
 * configuration classes by design, so the tests exercise them through the constructor and bean-method
 * signatures the container itself uses rather than by re-spelling key strings that could drift.
 *
 * <p><strong>Common failure modes.</strong> A failure in {@link ApplicationClockContract} means the
 * application cannot boot - every other test in the suite would still pass, because a unit test injects its
 * own clock, which is exactly how the defect survived to a review. A failure in
 * {@link CloudEndpointAllowListContract} means a build is capable of signing requests against live
 * infrastructure. Neither is a style regression.
 */
@DisplayName("Runtime wiring and cloud isolation - the contracts that decide whether the graph can be built")
final class RuntimeWiringAndAwsIsolationTest {

    /** The endpoint every accepted case uses, being the emulator's documented loopback address. */
    private static final String EMULATOR_ENDPOINT = "http://localhost:4566";

    /**
     * A deterministic non-live access-key placeholder.
     *
     * <p>The constructor refuses an absent credential and refuses any value beginning with a prefix AWS
     * assigns to real identifiers, so a test credential must be present and must not look real.
     */
    private static final String PLACEHOLDER_ACCESS_KEY = "test";

    /** The matching secret placeholder; never a real value, and never logged by the class under test. */
    private static final String PLACEHOLDER_SECRET_KEY = "test";

    // No SecurityConfig instance is built here. The clock bean moved to ObservabilityConfig, so the only
    // thing this suite now asks of SecurityConfig is what it declares - which is read reflectively, from the
    // class rather than from an instance. A constructed configuration together with the signing key, issuer
    // and BCrypt cost it needed would be dead code under Rule 1 Clause B, so it is gone rather than kept
    // "in case"; SecurityConfigTest is where that constructor's own contract is asserted.

    /**
     * Constructs an {@link AwsConfig} with valid resource names and the three supplied endpoints.
     *
     * @param s3Endpoint  the object-storage endpoint under test
     * @param sqsEndpoint the queue endpoint under test
     * @param snsEndpoint the notification endpoint under test
     * @return the constructed configuration, never {@code null}
     */
    private static AwsConfig awsConfig(final String s3Endpoint, final String sqsEndpoint,
            final String snsEndpoint) {

        return new AwsConfig(
                "us-east-1",
                "carddemo-batch-input",
                "carddemo-batch-output",
                "carddemo-statements",
                "carddemo-report-jobs.fifo",
                "carddemo-report-jobs",
                "carddemo-notifications",
                QueueNotFoundStrategy.FAIL,
                s3Endpoint,
                sqsEndpoint,
                snsEndpoint,
                // The library's single global override, which the per-service checks never see and which the
                // constructor therefore sweeps separately. Left unset here so each test controls exactly one
                // endpoint.
                "",
                // Deterministic non-live placeholders. The constructor refuses an absent credential outright -
                // an unset pair would let the SDK fall back to its default provider chain and sign with a real
                // principal - and refuses anything carrying a live access-key prefix.
                PLACEHOLDER_ACCESS_KEY,
                PLACEHOLDER_SECRET_KEY);
    }

    // =============================================================================================
    // 1. The single application Clock.
    // =============================================================================================

    /**
     * The {@link Clock} bean contract.
     *
     * <p>The defect this group exists to prevent is specific and was real: the production configuration
     * published no {@code Clock} while thirteen singleton constructors required one, so the context could not
     * be created - and the whole unit suite still passed, because a unit test constructs its subject with
     * {@code Clock.fixed(...)} and never asks the container for one.
     *
     * <p><strong>The declaration belongs to {@code ObservabilityConfig}, not to {@code SecurityConfig}.</strong>
     * Both were fitted with one at different times; two declarations of the same type is not a redundancy but a
     * startup failure, because {@code spring.main.allow-bean-definition-overriding} is {@code false}. The bean
     * lives with the other cross-cutting instrumentation and this group asserts both halves: exactly one
     * declaration there, and none in the security configuration.
     *
     * <p><strong>The deployment's own zone is the default, and UTC is a deliberate pin.</strong> The intrinsic
     * this clock replaces returned the region's local civil time, and every rendered date, time and
     * 26-character timestamp derives from it, so a hard-wired UTC would shift all of them. The storage side is
     * separately governed: {@code hibernate.jdbc.time_zone} is UTC, which is what makes a stored instant
     * unambiguous. A deployment that wants the two to coincide pins {@code carddemo.time.zone} to UTC, and
     * that pin is what the first test below exercises.
     */
    @Nested
    @DisplayName("the one java.time.Clock every time-dependent singleton is built with")
    final class ApplicationClockContract {

        @Test
        @DisplayName("ObservabilityConfig declares exactly one Clock bean method, and SecurityConfig none")
        void exactlyOneClockBeanMethod() {
            final List<Method> declared = Stream.of(ObservabilityConfig.class.getDeclaredMethods())
                    .filter(method -> Clock.class.equals(method.getReturnType()))
                    .filter(method -> method.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
                    .toList();

            assertThat(declared)
                    .as("thirteen singleton constructors take a Clock and all are built during context "
                            + "refresh, so exactly one bean must supply it; two would abort the refresh, "
                            + "because bean-definition overriding is disabled")
                    .hasSize(1);

            assertThat(Stream.of(SecurityConfig.class.getDeclaredMethods())
                            .filter(method -> Clock.class.equals(method.getReturnType()))
                            .filter(method ->
                                    method.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
                            .toList())
                    .as("a second declaration was twice added here; this is what makes a third fail at once")
                    .isEmpty();
        }

        @Test
        @DisplayName("a UTC pin is honoured, which is how a deployment aligns rendering with storage")
        void aUtcPinIsHonoured() {
            // ZoneId.of("UTC") is a fixed-offset region rather than the ZoneOffset constant, so the
            // comparison is on the normalised form: what matters is a zero offset that never varies, not
            // which of the two equivalent representations the factory returned.
            assertThat(new ObservabilityConfig().clock("UTC").getZone().normalized())
                    .as("every timestamp written to PostgreSQL is a UTC instant, so a deployment that wants "
                            + "app/cbl/CBTRN02C.cbl:L678-L706 timestamp text to read in the same zone as the "
                            + "row it accompanies pins this property to UTC rather than relying on the host")
                    .isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("with no pin the deployment's own zone applies, because the intrinsic was local")
        void withNoPinTheDeploymentZoneApplies() {
            assertThat(new ObservabilityConfig().clock("").getZone())
                    .as("FUNCTION CURRENT-DATE returned the region's local civil time, so an unpinned clock "
                            + "reproduces it; hard-wiring UTC would shift every rendered date and time by the "
                            + "deployment's offset")
                    .isEqualTo(java.time.ZoneId.systemDefault());
        }

        @Test
        @DisplayName("a deployment can pin a different zone deliberately")
        void zoneIsConfigurable() {
            assertThat(new ObservabilityConfig().clock("America/Chicago").getZone().getId())
                    .isEqualTo("America/Chicago");
        }

        @Test
        @DisplayName("surrounding whitespace is stripped rather than rejected")
        void zoneIsTrimmed() {
            assertThat(new ObservabilityConfig().clock("  UTC  ").getZone().normalized())
                    .isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("the clock is a system clock, not a fixed one")
        void clockAdvances() {
            final Clock clock = new ObservabilityConfig().clock("UTC");
            final java.time.Instant first = clock.instant();
            final java.time.Instant second = clock.instant();

            // A fixed clock published in production would freeze every issued-at and expiry claim, so the
            // assertion is that time does not go backwards and that the source is the system clock rather
            // than a snapshot. Equality with a captured instant is deliberately not asserted: two reads
            // inside the same millisecond legitimately return the same value on a coarse-grained platform.
            assertThat(second).isAfterOrEqualTo(first);
            assertThat(clock.getClass().getSimpleName())
                    .as("Clock.system(...) returns the JDK system clock implementation")
                    .isEqualTo("SystemClock");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Not/AZone", "${CARDDEMO_TIME_ZONE}", "UTC+bogus"})
        @DisplayName("an unusable pin aborts startup and names the offending value")
        void unusableZoneAbortsStartup(final String zoneId) {
            assertThatThrownBy(() -> new ObservabilityConfig().clock(zoneId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.time.zone")
                    .hasMessageContaining(zoneId);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("an absent pin is not an unusable one: blank selects the deployment default")
        void blankPinSelectsTheDeploymentDefault(final String zoneId) {
            // The placeholder carries an empty default, so blank is what the container passes when nothing is
            // configured - which is the shipped state of every profile. Treating it as a defect would make the
            // documented default unreachable.
            assertThat(new ObservabilityConfig().clock(zoneId).getZone())
                    .isEqualTo(java.time.ZoneId.systemDefault());
        }

        @Test
        @DisplayName("a null pin follows the same one rule as a blank one")
        void nullZoneSelectsTheDeploymentDefault() {
            // Unreachable from the container for the reason above, so it is defined rather than guarded: one
            // rule - absent, blank or null all mean unpinned - is checkable in one line, where two rules would
            // leave a caller asking which of them a null takes.
            assertThat(new ObservabilityConfig().clock(null).getZone())
                    .isEqualTo(java.time.ZoneId.systemDefault());
        }
    }

    // =============================================================================================
    // 2. The cloud endpoint allow list.
    // =============================================================================================

    /**
     * The emulator endpoint allow list.
     *
     * <p>Every case here drives the real constructor, because the check has to run before any other
     * validation for the invariant to hold: if the application can reach a live service then nothing else
     * about its configuration matters.
     */
    @Nested
    @DisplayName("no code path may reach live cloud infrastructure")
    final class CloudEndpointAllowListContract {

        /**
         * Endpoints an emulator genuinely answers on.
         *
         * @return the accepted cases
         */
        static Stream<String> acceptedEndpoints() {
            return Stream.of(
                    "http://localhost:4566",
                    "https://localhost:4566",
                    "http://LOCALHOST:4566",
                    "http://127.0.0.1:4566",
                    "http://[::1]:4566",
                    "http://localstack:4566",
                    "http://localhost.localstack.cloud:4566");
        }

        /**
         * Endpoints that are not the emulator, including the shapes a real service endpoint takes.
         *
         * @return the refused cases
         */
        static Stream<String> refusedEndpoints() {
            return Stream.of(
                    "https://s3.us-east-1.amazonaws.com",
                    "https://sqs.eu-west-1.amazonaws.com",
                    "https://sns.amazonaws.com",
                    "https://s3.localhost.localstack.cloud.attacker.example",
                    // A sub-domain of the emulator's loopback DNS name, bucket-prefixed. It resolves to
                    // loopback and was once accepted here on that ground. It is refused because
                    // localstack-init/init-aws.sh refuses it, having probed it: the virtual-hosted form
                    // passed readiness and then failed provisioning at the queue stage, so the value would
                    // start the application and then break at the second service that used it. Bucket-style
                    // request addressing is derived by the SDK from the bucket name, not configured here.
                    "http://carddemo-batch-input.s3.localhost.localstack.cloud:4566",
                    "https://notlocalhost:4566",
                    "http://169.254.169.254",
                    "ftp://localhost:4566",
                    "localhost:4566",
                    "http://",
                    "   ");
        }

        @ParameterizedTest
        @MethodSource("acceptedEndpoints")
        @DisplayName("an emulator endpoint is accepted on all three services")
        void emulatorEndpointAccepted(final String endpoint) {
            assertThat(awsConfig(endpoint, endpoint, endpoint)).isNotNull();
        }

        @ParameterizedTest
        @MethodSource("refusedEndpoints")
        @DisplayName("a non-emulator endpoint aborts startup and names the value and the key")
        void nonEmulatorEndpointRefused(final String endpoint) {
            assertThatThrownBy(() -> awsConfig(endpoint, EMULATOR_ENDPOINT, EMULATOR_ENDPOINT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint")
                    .hasMessageContaining("no flag that relaxes this check");
        }

        @Test
        @DisplayName("an absent endpoint aborts startup rather than falling through to real resolution")
        void absentEndpointRefused() {
            assertThatThrownBy(() -> awsConfig(null, EMULATOR_ENDPOINT, EMULATOR_ENDPOINT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resolved to no value");
        }

        @Test
        @DisplayName("each of the three services is checked independently")
        void everyServiceIsChecked() {
            final String live = "https://sqs.us-east-1.amazonaws.com";

            assertThatThrownBy(() -> awsConfig(EMULATOR_ENDPOINT, live, EMULATOR_ENDPOINT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sqs.endpoint");
            assertThatThrownBy(() -> awsConfig(EMULATOR_ENDPOINT, EMULATOR_ENDPOINT, live))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sns.endpoint");
        }

        @Test
        @DisplayName("the endpoint check runs before the resource-name checks")
        void endpointCheckRunsFirst() {
            // Both a live endpoint and a blank bucket name are supplied. The endpoint must be the reported
            // defect: it is the invariant, and reporting the bucket first would let a reader conclude that
            // fixing the bucket is sufficient.
            assertThatThrownBy(() -> new AwsConfig(
                    "us-east-1", "  ", "carddemo-batch-output", "carddemo-statements",
                    "carddemo-report-jobs.fifo", "carddemo-report-jobs", "carddemo-notifications",
                    QueueNotFoundStrategy.FAIL,
                    "https://s3.us-east-1.amazonaws.com", EMULATOR_ENDPOINT, EMULATOR_ENDPOINT,
                    "", PLACEHOLDER_ACCESS_KEY, PLACEHOLDER_SECRET_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint");
        }

        @Test
        @DisplayName("the constructor takes all three endpoints, so none can be forgotten")
        void constructorBindsAllThreeEndpoints() {
            final Constructor<?>[] constructors = AwsConfig.class.getConstructors();
            assertThat(constructors).hasSize(1);

            final long endpointParameters = Stream.of(constructors[0].getParameters())
                    .map(Parameter::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("endpoint"))
                    .count();
            assertThat(endpointParameters)
                    .as("one parameter per service plus the library's single global override, which the "
                            + "per-service checks never see and which would otherwise point every client at a "
                            + "live endpoint while all three service properties looked correct")
                    .isEqualTo(4L);
        }
    }

    // =============================================================================================
    // 3. The one anonymous route.
    // =============================================================================================

    /**
     * The single anonymous sign-on route.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:L378} defines exactly one unauthenticated transaction, {@code CC00}
     * fronting {@code app/cbl/COSGN00C.cbl}. A prefix pattern would grant anonymity to paths that do not
     * exist yet, which is the opposite of what {@code anyRequest().denyAll()} is there to guarantee.
     */
    @Nested
    @DisplayName("exactly one route answers anonymously, and it is a concrete path")
    final class AnonymousSignOnRouteContract {

        @Test
        @DisplayName("the sign-on path carries no wildcard segment")
        void signOnPathIsExact() throws Exception {
            final java.lang.reflect.Field field = SecurityConfig.class.getDeclaredField("PATH_SIGN_ON");
            field.setAccessible(true);
            final String path = (String) field.get(null);

            assertThat(path)
                    .as("a /api/auth/** prefix would make every future authentication operation anonymous "
                            + "by accident rather than by decision")
                    .doesNotContain("*")
                    .isEqualTo("/api/auth/signon");
        }
    }
}
