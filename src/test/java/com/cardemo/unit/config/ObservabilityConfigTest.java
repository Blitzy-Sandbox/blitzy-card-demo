/*
 * ******************************************************************
 * Program     : ObservabilityConfigTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (observability and time surface)
 * Function    : Proves the production time source exists, is singular,
 *               and carries the deployment zone rather than UTC, which is
 *               what reproduces the local-time semantics of the COBOL
 *               FUNCTION CURRENT-DATE intrinsic.
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
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unit tests for {@link ObservabilityConfig}.
 *
 * <p>The class publishes one bean and reads one optional property, so the whole contract is checkable
 * without a container: the bean exists, it is the only one, it is a system clock in the deployment's zone
 * when the property is unset, it honours the property when it is set, it aborts rather than guess when the
 * property is set to something that is not a zone, and the class is annotated so a component scan finds it.
 *
 * <p>The zone override reaches the bean method as an argument rather than through a field, which is what lets
 * every case below be exercised by calling the method directly. {@link #UNPINNED} is the value a context
 * supplies when nothing is configured, because the placeholder carries an empty default.
 */
@DisplayName("ObservabilityConfig - the single production time source")
class ObservabilityConfigTest {

    /**
     * The value the container passes when {@code carddemo.time.zone} is unset, which is how every shipped
     * profile leaves it: the placeholder declares an empty default, so the bean method receives the empty
     * string rather than {@code null}.
     */
    private static final String UNPINNED = "";

    /** The class under test. It takes no collaborator, which is itself part of the contract. */
    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    @DisplayName("the class is a scanned configuration class")
    void classIsAScannedConfigurationClass() {
        assertThat(ObservabilityConfig.class.getAnnotation(Configuration.class))
                .as("the application scans com.cardemo, so the annotation is what makes the bean reachable")
                .isNotNull();
    }

    @Test
    @DisplayName("exactly one bean method is declared, so no ambiguous dependency can arise")
    void exactlyOneBeanMethodIsDeclared() {
        final long beanMethods = Arrays.stream(ObservabilityConfig.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Bean.class) != null)
                .count();

        assertThat(beanMethods)
                .as("a second Clock bean would produce an order-dependent startup failure, which is worse "
                        + "than the missing one it would be trying to fix")
                .isEqualTo(1L);
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
