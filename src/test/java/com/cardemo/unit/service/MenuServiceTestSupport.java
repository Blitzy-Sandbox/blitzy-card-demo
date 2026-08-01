/*
 * ******************************************************************
 * Program     : MenuServiceTestSupport.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Owns the mechanisms both menu-service test classes
 *               need: reaching the package-private (Clock, List)
 *               construction seam reflectively, reading the header
 *               argument back off a logback ListAppender, and
 *               asserting an invalid-option refusal. Each mechanism
 *               was previously written twice, identically or
 *               differing only in a literal, so it now lives here
 *               once while every caller keeps its own literals.
 * Source      : app/cbl/COMEN01C.cbl, app/cbl/COADM01C.cbl  (the two
 *               menu programs), app/cpy/COMEN02Y.cpy,
 *               app/cpy/COADM02Y.cpy  (their option tables)
 *               frozen at commit 7756d89
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.ValidationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The mechanisms shared by the two menu-service test classes.
 *
 * <p>Both {@code COMEN01C} and {@code COADM01C} render their screen furniture by moving the current
 * date and time into header fields, and neither returns that furniture to a caller. The Java
 * services log it instead, so the only way to assert it without changing production code is to
 * attach a logback {@link ListAppender} and read the logged argument. Both classes also need to
 * reach the package-private {@code (Clock, List)} construction seam, because the branches that no
 * canonical option exercises &mdash; a placeholder target, and the administrator-only gate whose
 * triggering data was commented out of the copybook &mdash; are reachable only through it.</p>
 *
 * <h2>Why the varying part is a parameter rather than a constant</h2>
 *
 * <p>Every helper here takes the caller's own literal: the log-message prefix, the assertion
 * description, the expected refusal message, the absence message. That is deliberate. The two
 * services deliberately differ in four observable ways &mdash; the coming-soon message, the
 * placeholder target, the empty-list policy and the label rendering &mdash; and those differences
 * are preserved behaviour, not drift. Folding any of these literals into a single shared constant
 * would quietly assert that the two services agree where the corpus says they do not.</p>
 *
 * @see com.cardemo.unit.model.ValidationSupport
 */
final class MenuServiceTestSupport {

    /**
     * Prevents instantiation. Every member is static.
     */
    private MenuServiceTestSupport() {
        throw new AssertionError("MenuServiceTestSupport is a static holder and must not be instantiated");
    }

    /**
     * Resolves the package-private {@code (Clock, List)} construction seam on a menu service.
     *
     * @param serviceType   the menu service to inspect; must not be {@code null}
     * @param absenceReason the caller's own explanation of what becomes unassertable if the seam is
     *                      missing; must not be {@code null}
     * @return the declared constructor, never {@code null}
     * @throws AssertionError if the service declares no such constructor
     */
    static Constructor<?> seamConstructor(final Class<?> serviceType, final String absenceReason) {
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Objects.requireNonNull(absenceReason, "absenceReason must not be null");
        try {
            return serviceType.getDeclaredConstructor(Clock.class, List.class);
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError(serviceType.getSimpleName()
                    + " declares no (Clock, List) constructor, so " + absenceReason, absent);
        }
    }

    /**
     * Invokes a construction seam and unwraps a validation failure back into the caller's view.
     *
     * <p>Reflective invocation wraps whatever the constructor throws in an
     * {@link InvocationTargetException}, which would defeat every guard assertion the callers make.
     * A runtime cause is therefore rethrown unchanged so that a refusal raised inside the seam
     * reaches the test exactly as it would from a direct call.</p>
     *
     * @param serviceType the expected service type, used to cast the result; must not be
     *                    {@code null}
     * @param seam        the constructor to invoke; must not be {@code null}
     * @param clock       the fixed clock to inject
     * @param options     the option table to inject
     * @param <T>         the menu service type
     * @return the constructed service
     * @throws AssertionError if the seam throws a checked exception or cannot be invoked
     */
    static <T> T construct(final Class<T> serviceType, final Constructor<?> seam, final Clock clock,
            final List<?> options) {
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Objects.requireNonNull(seam, "seam must not be null");
        try {
            seam.setAccessible(true);
            return serviceType.cast(seam.newInstance(clock, options));
        } catch (final InvocationTargetException invocationFailure) {
            final Throwable cause = invocationFailure.getCause();
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new AssertionError("seam threw a checked exception", cause);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("the (Clock, List) seam could not be invoked on "
                    + serviceType.getSimpleName(), failure);
        }
    }

    /**
     * Selects the header-rendering log events from an appender by message prefix.
     *
     * @param appender      the attached appender; must not be {@code null}
     * @param messagePrefix the caller's own log-message prefix; must not be {@code null}
     * @return the matching events in logged order, never {@code null}
     */
    static List<ILoggingEvent> headerEvents(final ListAppender<ILoggingEvent> appender,
            final String messagePrefix) {
        Objects.requireNonNull(appender, "appender must not be null");
        Objects.requireNonNull(messagePrefix, "messagePrefix must not be null");
        return appender.list.stream()
                .filter(event -> event.getMessage().startsWith(messagePrefix))
                .toList();
    }

    /**
     * Asserts that exactly one header event was logged and returns it.
     *
     * <p>The count is asserted rather than assumed because {@code POPULATE-HEADER-INFO} runs once
     * per send in both source programs; a second event would mean the Java service sends twice.</p>
     *
     * @param events      the selected header events; must not be {@code null}
     * @param description the caller's own explanation of why exactly one is expected; must not be
     *                    {@code null}
     * @return the single event
     */
    static ILoggingEvent singleHeaderEvent(final List<ILoggingEvent> events, final String description) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(description, "description must not be null");
        assertThat(events).as(description).hasSize(1);
        return events.get(0);
    }

    /**
     * Returns a logged event's argument array, asserting that the statement is parameterised.
     *
     * @param event       the event to read; must not be {@code null}
     * @param description the caller's own explanation of why an argument array must be present;
     *                    must not be {@code null}
     * @return the argument array, never {@code null}
     */
    static Object[] argumentsOf(final ILoggingEvent event, final String description) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(description, "description must not be null");
        final Object[] arguments = event.getArgumentArray();
        assertThat(arguments).as(description).isNotNull();
        return arguments;
    }

    /**
     * Asserts that an action is refused as an invalid menu option, naming the option field.
     *
     * @param action          the call expected to be refused; must not be {@code null}
     * @param expectedMessage the caller's own expected refusal message; must not be {@code null}
     * @param expectedField   the caller's own expected field name; must not be {@code null}
     */
    static void assertInvalidOption(final Runnable action, final String expectedMessage,
            final String expectedField) {
        Objects.requireNonNull(action, "action must not be null");
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(action::run)
                .withMessage(expectedMessage)
                .satisfies(rejection -> assertThat(rejection.getFieldName()).isEqualTo(expectedField));
    }

    /**
     * Runs an action and returns the {@link ValidationException} it must raise.
     *
     * <p>Returning the exception rather than asserting on it in place is what lets a caller make
     * several independent assertions about one refusal &mdash; message, field name and failure kind
     * &mdash; without provoking it repeatedly.</p>
     *
     * @param action the call expected to be refused; must not be {@code null}
     * @return the raised exception
     * @throws AssertionError if the action returns normally
     */
    static ValidationException catchValidation(final Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        try {
            action.run();
        } catch (final ValidationException expected) {
            return expected;
        }
        throw new AssertionError("expected a ValidationException, but the call returned normally");
    }
}
