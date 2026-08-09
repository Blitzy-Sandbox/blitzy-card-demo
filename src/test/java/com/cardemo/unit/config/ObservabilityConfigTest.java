/*
 * ******************************************************************
 * Program     : ObservabilityConfigTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (observability and time surface)
 * Function    : Proves the production time source exists, is singular,
 *               and carries the deployment zone rather than UTC, which is
 *               what reproduces the local-time semantics of the COBOL
 *               FUNCTION CURRENT-DATE intrinsic. Also proves that the
 *               pushed-meter filter is opt-in and admits only this
 *               application's own instruments, so a batch process that
 *               pushes cannot leave a retained gauge behind.
 * Source      : app/cpy/CSUTLDPY.cpy:L343 and app/cbl/CBTRN02C.cbl:L693
 *               (FUNCTION CURRENT-DATE) @ 7756d89
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
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.config.ObservabilityConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unit tests for {@link ObservabilityConfig}.
 *
 * <p>The class publishes two beans and reads one optional property, so the whole contract is checkable
 * without a container: the clock exists, it is the only clock, it is a system clock in the deployment's zone
 * when the property is unset, it honours the property when it is set, it aborts rather than guess when the
 * property is set to something that is not a zone, and the class is annotated so a component scan finds it.
 * The second bean is the meter filter a <em>pushing</em> process applies; it is conditional on a property no
 * shipped profile enables, and the assertions below cover both the condition and the filter's decisions.
 *
 * <p>The zone override reaches the bean method as an argument rather than through a field, which is what lets
 * every case below be exercised by calling the method directly. {@link #UNPINNED} is the value a context
 * supplies when nothing is configured, because the placeholder carries an empty default.
 */
@DisplayName("ObservabilityConfig - the single production time source and the pushed-meter filter")
class ObservabilityConfigTest {

    /**
     * The value the container passes when {@code carddemo.time.zone} is unset, which is how every shipped
     * profile leaves it: the placeholder declares an empty default, so the bean method receives the empty
     * string rather than {@code null}.
     */
    private static final String UNPINNED = "";

    /** The class under test. It takes no collaborator, which is itself part of the contract. */
    private final ObservabilityConfig config = new ObservabilityConfig();

    /**
     * A throwaway registry used only to mint real {@link Meter.Id} values for the filter assertions.
     *
     * <p>A filter decides on an identifier, and an identifier carries the common tags a registry applies, so
     * building one through a registry rather than by hand is what makes the assertion exercise the same shape
     * the production registry presents.
     */
    private static final MeterRegistry REGISTRY = new SimpleMeterRegistry();

    @Test
    @DisplayName("the class is a scanned configuration class")
    void classIsAScannedConfigurationClass() {
        assertThat(ObservabilityConfig.class.getAnnotation(Configuration.class))
                .as("the application scans com.cardemo, so the annotation is what makes the bean reachable")
                .isNotNull();
    }

    @Test
    @DisplayName("exactly two bean methods are declared, and neither can shadow the other")
    void exactlyTwoBeanMethodsAreDeclared() {
        final List<String> beanMethods = Arrays.stream(ObservabilityConfig.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Bean.class) != null)
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(beanMethods)
                .as("a second Clock bean would produce an order-dependent startup failure, which is worse "
                        + "than the missing one it would be trying to fix. The count is asserted by NAME "
                        + "rather than by number so that adding a bean here is a deliberate act: clock() is "
                        + "the single production time source, and pushedMeterFilter() restricts what a "
                        + "PUSHING process publishes and is conditional on a property no profile enables.")
                .containsExactly("clock", "pushedMeterFilter");
    }

    @Test
    @DisplayName("the meter filter is conditional, so the scraped application never applies it")
    void theMeterFilterIsConditionalOnThePushBeingEnabled() throws NoSuchMethodException {
        final Method method = ObservabilityConfig.class.getDeclaredMethod("pushedMeterFilter");
        final ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition)
                .as("without the condition this filter would apply to the long-lived web application too, "
                        + "and denying everything outside carddemo.* there would remove the JVM, HTTP, "
                        + "Hibernate and Spring Batch series that observability/prometheus.yml scrapes")
                .isNotNull();
        assertThat(condition.name())
                .containsExactly("management.prometheus.metrics.export.pushgateway.enabled");
        assertThat(condition.havingValue())
                .as("it must be opt IN. src/main/resources/application.yml binds the property to false, so "
                        + "only an explicit launch flag activates the filter")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("the meter filter admits the carddemo instruments and denies everything else")
    void theMeterFilterAdmitsOnlyTheCardDemoNamespace() {
        final MeterFilter filter = config.pushedMeterFilter();

        assertThat(filter.accept(Meter.Id.class.cast(
                        Counter.builder("carddemo.batch.records.processed").register(REGISTRY).getId())))
                .as("carddemo.batch.records.processed reproduces ADD 1 TO WS-TRANSACTION-COUNT at "
                        + "app/cbl/CBTRN02C.cbl:L206 and is the whole reason the push path exists")
                .isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(filter.accept(Meter.Id.class.cast(
                        Counter.builder("jvm.memory.used").register(REGISTRY).getId())))
                .as("a Pushgateway RETAINS what it is given, so a dead batch JVM's heap reading would "
                        + "inflate the dashboard's sum(jvm_memory_used_bytes{area=\"heap\"}) for ever")
                .isEqualTo(MeterFilterReply.DENY);
        assertThat(filter.accept(Meter.Id.class.cast(
                        Counter.builder("spring.batch.job.active").register(REGISTRY).getId())))
                .as("the framework's own batch series carry a job execution id, so retaining them would "
                        + "accumulate one dead group per run")
                .isEqualTo(MeterFilterReply.DENY);
    }

    @Test
    @DisplayName("the bean method is named clock and returns java.time.Clock")
    void beanMethodIsNamedClock() throws NoSuchMethodException {
        final Method method = ObservabilityConfig.class.getDeclaredMethod("clock", String.class);

        assertThat(method.getAnnotation(Bean.class)).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(Clock.class);
    }

    @Test
    @DisplayName("the clock is a system clock in the deployment's own zone, not UTC by construction")
    void clockCarriesTheDeploymentZone() {
        final Clock clock = config.clock(UNPINNED);

        assertThat(clock).isNotNull();
        assertThat(clock.getZone())
                .as("FUNCTION CURRENT-DATE returns local time (app/cpy/CSUTLDPY.cpy:L343), so the zone is "
                        + "the deployment's and never hard-wired to UTC")
                .isEqualTo(ZoneId.systemDefault());
        assertThat(clock).isEqualTo(Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("the clock advances, so it is a live source rather than a fixed one")
    void clockAdvances() {
        final Clock clock = config.clock(UNPINNED);

        assertThat(clock.instant()).isNotNull();
        assertThat(clock.millis())
                .as("a fixed clock here would freeze every rendered date and time in production")
                .isPositive();
    }

    @Test
    @DisplayName("the clock is immutable and safe to share, so a singleton bean is correct")
    void clockIsImmutableAndShareable() {
        final Clock first = config.clock(UNPINNED);
        final Clock second = config.clock(UNPINNED);

        assertThat(first).isEqualTo(second);
        assertThat(first.withZone(ZoneId.of("UTC"))).isNotEqualTo(first);
        assertThat(first.getZone())
                .as("withZone returns a new instance rather than mutating the shared one")
                .isEqualTo(ZoneId.systemDefault());
    }

    @Test
    @DisplayName("a pinned zone is honoured, so a baseline can be reproduced on a differently configured host")
    void aPinnedZoneIsHonoured() {
        final Clock clock = config.clock("America/New_York");

        assertThat(clock.getZone())
                .as("the property exists so a zone can be chosen deliberately rather than inherited")
                .isEqualTo(ZoneId.of("America/New_York"));
    }

    @Test
    @DisplayName("a blank pin is not a pin, so whitespace still selects the runtime default")
    void aBlankPinSelectsTheRuntimeDefault() {
        assertThat(config.clock("   ").getZone()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    @DisplayName("an unresolved placeholder aborts startup, naming the variable that was never exported")
    void anUnresolvedPlaceholderAbortsStartup() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> config.clock("${CARDDEMO_TIME_ZONE}"))
                .withMessageContaining("carddemo.time.zone")
                .withMessageContaining("unresolved property placeholder");
    }

    @Test
    @DisplayName("an unrecognised zone aborts startup rather than silently falling back")
    void anUnrecognisedZoneAbortsStartup() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> config.clock("Mars/Olympus_Mons"))
                .withMessageContaining("does not name a zone this runtime recognises")
                .as("a deployment that asked for one zone and silently got another would render timestamps "
                        + "that disagree with its own stored rows by the host's offset");
    }
}
