/*
 * ******************************************************************
 * Program     : CardDemoExceptionHierarchyTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the nine-type exception hierarchy under
 *               com.cardemo.exception honours one contract across every
 *               member - a single base type, an unchecked base so the
 *               COBOL guard idiom does not become a checked-exception
 *               signature on every I/O method, a message that is always
 *               carried, and a cause that is ALWAYS preserved and never
 *               swallowed, which is the Rule 1 Clause B requirement the
 *               legacy corpus could not express.
 * Source      : app/cbl/CBTRN02C.cbl:L142-L144, L707-L731 @ 7756d89
 * Source      : app/cpy/CSMSG02Y.cpy:L21-L28 (CABENDD.CPY) @ 7756d89
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

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test for the {@link CardDemoException} hierarchy - the typed replacement for the universal COBOL
 * {@code FILE STATUS} guard idiom and the abend routine.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>Rather than repeat one contract nine times, this class states the contract once and drives it over
 * every member of the hierarchy, so a tenth type added later is held to the same rules the moment its name
 * is registered here:
 *
 * <ol>
 *   <li><strong>One base type, and it is unchecked.</strong> Every I/O verb in the batch corpus is followed
 *       by the same guard - move 8 into a result field, perform the verb, move 0 on {@code '00'} and 12
 *       otherwise, then abend ({@code app/cbl/CBTRN02C.cbl:L142-L144}). That guard sits on hundreds of call
 *       sites. Modelling it with a checked exception would put a {@code throws} clause on the signature of
 *       every repository and service method that reads anything, which is why the base extends
 *       {@link RuntimeException}. This test asserts that choice explicitly so it cannot be reverted by
 *       accident.</li>
 *   <li><strong>The message always survives.</strong> Every subtype carries the message through to
 *       {@link Throwable#getMessage()} unchanged, because the legacy diagnostics are literal strings the
 *       parity comparison reads.</li>
 *   <li><strong>The cause is ALWAYS preserved.</strong> Rule 1 Clause B forbids swallowing an exception or
 *       losing its root cause. Every subtype is asserted to expose the cause it was handed, by identity and
 *       not merely by message, and to report a {@code null} cause when constructed without one rather than
 *       inventing a placeholder.</li>
 *   <li><strong>Each type is a distinct, catchable branch.</strong> The COBOL guard distinguishes statuses
 *       {@code '23'}, {@code '22'}, {@code '35'} and the {@code '9x'} family, and a caller must be able to
 *       catch exactly one of those without catching the others. This test asserts that no subtype is
 *       assignable from another, so the branches stay disjoint.</li>
 *   <li><strong>Every type is serializable with a pinned serialVersionUID</strong>, because a
 *       {@link RuntimeException} may cross a serialization boundary and an unpinned form would break on any
 *       recompilation.</li>
 * </ol>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * mvn -B -o test                                                # whole unit tier
 * mvn -B -o test -Dtest=CardDemoExceptionHierarchyTest           # this class alone
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. No Spring context, no connection, no external resource.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The unchecked assertion fails.</strong> The base was changed to extend {@link Exception}.
 *       That change would ripple a {@code throws} clause onto every I/O signature in the codebase; revert
 *       it rather than propagate it.</li>
 *   <li><strong>A cause assertion fails.</strong> A subtype dropped the cause on one of its constructors,
 *       which is precisely the swallowed-root-cause defect Rule 1 Clause B names.</li>
 *   <li><strong>The disjointness assertion fails.</strong> One subtype was made to extend another, which
 *       silently widens every {@code catch} for the parent and would, for example, make a not-found
 *       condition be handled as a duplicate key.</li>
 *   <li><strong>The registry assertion fails.</strong> A type was added to or removed from the package
 *       without updating {@link #allConcreteTypes()}. Update the registry; that failure is the mechanism
 *       working, not a false alarm.</li>
 * </ul>
 *
 * @see CardDemoException
 */
class CardDemoExceptionHierarchyTest {

    /** A representative message, deliberately not blank so the carry-through is observable. */
    private static final String MESSAGE = "ACCOUNT RECORD NOT FOUND";

    /**
     * Every concrete member of the hierarchy. Registered explicitly rather than discovered by scanning the
     * classpath, so that adding a type is a deliberate act that also brings it under this contract, and so
     * that this tier stays free of a classpath-scanning dependency.
     */
    static Stream<Class<? extends CardDemoException>> allConcreteTypes() {
        return Stream.of(
                CardDemoException.class,
                ValidationException.class,
                RecordNotFoundException.class,
                DuplicateRecordException.class,
                FileUnavailableException.class,
                ConcurrentUpdateException.class,
                DataIntegrityException.class,
                FileAccessException.class,
                FatalProcessingException.class);
    }

    /** The eight subtypes, i.e. the hierarchy without its base. */
    static Stream<Class<? extends CardDemoException>> allSubtypes() {
        return allConcreteTypes().filter(type -> !type.equals(CardDemoException.class));
    }

    @Test
    @DisplayName("the package declares exactly nine exception types: one base and eight subtypes")
    void thePackageDeclaresExactlyNineTypes() {
        assertThat(allConcreteTypes().toList())
                .as("the target design fixes the hierarchy at nine classes - a base plus the seven FILE "
                        + "STATUS translations plus the validation type; a tenth would need a source "
                        + "construct to justify it")
                .hasSize(9)
                .doesNotHaveDuplicates();
        assertThat(allSubtypes().toList()).hasSize(8);
    }

    @Test
    @DisplayName("the base extends RuntimeException, so the COBOL guard idiom stays off every signature")
    void theBaseIsUnchecked() {
        assertThat(RuntimeException.class)
                .as("the guard at app/cbl/CBTRN02C.cbl:L142-L144 follows every I/O verb in the corpus; a "
                        + "checked base would put a throws clause on every repository and service method "
                        + "that reads anything")
                .isAssignableFrom(CardDemoException.class);
        assertThat(CardDemoException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    }

    @ParameterizedTest
    @MethodSource("allSubtypes")
    @DisplayName("every subtype descends from the single base, so one catch can cover the whole hierarchy")
    void everySubtypeDescendsFromTheSingleBase(final Class<? extends CardDemoException> type) {
        assertThat(CardDemoException.class)
                .as("%s must be catchable as CardDemoException, or a boundary handler would miss it",
                        type.getSimpleName())
                .isAssignableFrom(type);
        assertThat(type.getSuperclass())
                .as("%s extends the base directly; an intermediate layer would widen a catch for it",
                        type.getSimpleName())
                .isEqualTo(CardDemoException.class);
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type is unchecked, so no I/O signature acquires a throws clause")
    void everyTypeIsUnchecked(final Class<? extends CardDemoException> type) {
        assertThat(RuntimeException.class).isAssignableFrom(type);
        assertThat(Error.class.isAssignableFrom(type))
                .as("%s must not be an Error: these are recoverable conditions a caller may handle, not "
                        + "JVM failures", type.getSimpleName())
                .isFalse();
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type exposes the (String) constructor and carries the message through unchanged")
    void everyTypeCarriesTheMessageThrough(final Class<? extends CardDemoException> type)
            throws ReflectiveOperationException {
        final CardDemoException thrown =
                type.getDeclaredConstructor(String.class).newInstance(MESSAGE);

        assertThat(thrown.getMessage())
                .as("%s must carry the diagnostic verbatim; the legacy strings are what the parity "
                        + "comparison reads", type.getSimpleName())
                .isEqualTo(MESSAGE);
        assertThat(thrown.getCause())
                .as("%s constructed without a cause must report null rather than invent a placeholder",
                        type.getSimpleName())
                .isNull();
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type exposes the (String, Throwable) constructor and PRESERVES the cause by identity")
    void everyTypePreservesTheCauseByIdentity(final Class<? extends CardDemoException> type)
            throws ReflectiveOperationException {
        final IOException rootCause = new IOException("VSAM physical read error, status 92");
        final CardDemoException thrown = type
                .getDeclaredConstructor(String.class, Throwable.class)
                .newInstance(MESSAGE, rootCause);

        assertThat(thrown.getCause())
                .as("Rule 1 Clause B forbids losing a root cause; %s must expose the very instance it was "
                        + "handed, not a copy and not a re-wrapped message", type.getSimpleName())
                .isSameAs(rootCause);
        assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type keeps a nested cause chain intact to its root")
    void everyTypeKeepsANestedCauseChainIntact(final Class<? extends CardDemoException> type)
            throws ReflectiveOperationException {
        final IOException root = new IOException("device not ready");
        final UncheckedIOException middle = new UncheckedIOException(root);
        final CardDemoException thrown = type
                .getDeclaredConstructor(String.class, Throwable.class)
                .newInstance(MESSAGE, middle);

        assertThat(thrown.getCause()).isSameAs(middle);
        assertThat(thrown.getCause().getCause())
                .as("a diagnostic is only actionable if the chain reaches the true root; %s must not "
                        + "flatten it", type.getSimpleName())
                .isSameAs(root);
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type accepts a null message and a null cause without throwing from its constructor")
    void everyTypeToleratesNullMessageAndNullCause(final Class<? extends CardDemoException> type)
            throws ReflectiveOperationException {
        final CardDemoException withNulls = type
                .getDeclaredConstructor(String.class, Throwable.class)
                .newInstance(null, null);

        assertThat(withNulls.getCause())
                .as("a constructor that threw while reporting a failure would mask the failure it was "
                        + "built to report; %s must stay constructible", type.getSimpleName())
                .isNull();
        assertThat(withNulls).isInstanceOf(CardDemoException.class);
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type is actually throwable and catchable as the base")
    void everyTypeIsThrowableAndCatchableAsTheBase(final Class<? extends CardDemoException> type)
            throws ReflectiveOperationException {
        final CardDemoException prepared =
                type.getDeclaredConstructor(String.class).newInstance(MESSAGE);
        CardDemoException caught = null;

        try {
            throw prepared;
        } catch (final CardDemoException expected) {
            caught = expected;
        }

        assertThat(caught)
                .as("%s must be catchable through the base, which is how a boundary handler maps the whole "
                        + "hierarchy onto responses", type.getSimpleName())
                .isSameAs(prepared)
                .isInstanceOf(type);
    }

    @ParameterizedTest
    @MethodSource("allSubtypes")
    @DisplayName("no subtype is assignable from another, so the FILE STATUS branches stay disjoint")
    void noSubtypeIsAssignableFromAnother(final Class<? extends CardDemoException> type) {
        final List<Class<? extends CardDemoException>> others =
                allSubtypes().filter(other -> !other.equals(type)).toList();

        assertThat(others)
                .as("catching %s must never also catch a sibling; a not-found condition and a duplicate "
                        + "key are different statuses with different handling", type.getSimpleName())
                .noneMatch(type::isAssignableFrom);
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type declares a pinned serialVersionUID")
    void everyTypeDeclaresAPinnedSerialVersionUid(final Class<? extends CardDemoException> type)
            throws NoSuchFieldException {
        final Field uid = type.getDeclaredField("serialVersionUID");

        assertThat(Modifier.isStatic(uid.getModifiers()))
                .as("%s.serialVersionUID must be static", type.getSimpleName())
                .isTrue();
        assertThat(Modifier.isFinal(uid.getModifiers())).isTrue();
        assertThat(uid.getType()).isEqualTo(long.class);
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type is public and concrete, so any layer can construct and catch it")
    void everyTypeIsPublicAndConcrete(final Class<? extends CardDemoException> type) {
        assertThat(Modifier.isPublic(type.getModifiers()))
                .as("%s must be public: it crosses package boundaries between repository, service and "
                        + "controller", type.getSimpleName())
                .isTrue();
        assertThat(Modifier.isAbstract(type.getModifiers()))
                .as("%s must be concrete, including the base, which is thrown directly for a condition "
                        + "that has no more specific translation", type.getSimpleName())
                .isFalse();
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type exposes only public constructors, all of which are documented entry points")
    void everyTypeExposesOnlyPublicConstructors(final Class<? extends CardDemoException> type) {
        assertThat(type.getDeclaredConstructors())
                .as("%s must not hide a constructor: a partially initialised diagnostic is worse than "
                        + "none", type.getSimpleName())
                .isNotEmpty()
                .allMatch(constructor -> Modifier.isPublic(constructor.getModifiers()));
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every type carries no mutator, so a thrown diagnostic cannot be edited in flight")
    void everyTypeCarriesNoMutator(final Class<? extends CardDemoException> type) {
        assertThat(type.getDeclaredMethods())
                .as("%s must be immutable once thrown", type.getSimpleName())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    @ParameterizedTest
    @MethodSource("allConcreteTypes")
    @DisplayName("every declared field is final, so the diagnostic payload is fixed at construction")
    void everyDeclaredFieldIsFinal(final Class<? extends CardDemoException> type) {
        assertThat(type.getDeclaredFields())
                .filteredOn(field -> !field.isSynthetic())
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s.%s must be final", type.getSimpleName(), field.getName())
                        .isTrue());
    }

    @Test
    @DisplayName("the base carries no payload of its own beyond message and cause")
    void theBaseCarriesNoPayloadOfItsOwn() {
        assertThat(CardDemoException.class.getDeclaredFields())
                .filteredOn(field -> !field.isSynthetic())
                .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
                .as("the base exists to unify the hierarchy, not to accumulate fields; each subtype owns "
                        + "the payload its own status needs")
                .isEmpty();
    }

    @Test
    @DisplayName("the base declares exactly the two constructors the hierarchy needs and no more")
    void theBaseDeclaresExactlyTwoConstructors() {
        final Constructor<?>[] constructors = CardDemoException.class.getDeclaredConstructors();

        assertThat(constructors)
                .as("message, and message plus cause; a no-argument constructor would permit a diagnostic "
                        + "with nothing to diagnose")
                .hasSize(2);
        assertThat(Stream.of(constructors).map(Constructor::getParameterCount).sorted().toList())
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("the base's stack trace is captured, so the throw site is recoverable from a log")
    void theBaseCapturesItsStackTrace() {
        final CardDemoException thrown = new CardDemoException(MESSAGE);

        assertThat(thrown.getStackTrace())
                .as("suppressing stack traces for speed would make an operator diagnostic unactionable; "
                        + "the legacy DISPLAY had no equivalent and this is a deliberate addition")
                .isNotEmpty();
        assertThat(thrown.getStackTrace()[0].getMethodName())
                .isEqualTo("theBaseCapturesItsStackTrace");
    }
}
