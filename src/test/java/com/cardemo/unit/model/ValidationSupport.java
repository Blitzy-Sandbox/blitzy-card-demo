/*
 * ******************************************************************
 * Program     : ValidationSupport.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Owns the one bean-validation Validator shared by every
 *               DTO test, and the one guarded entry point that runs a
 *               DTO through it. Ten test classes previously each
 *               bootstrapped their own ValidatorFactory and each
 *               declared their own violationsOf helper in one of four
 *               divergent forms; the mechanism now lives here once
 *               while each call site keeps its own parameter name, so
 *               every asserted refusal message is preserved exactly.
 * Source      : app/cpy/CSSETATY.cpy  (the parameterised field-error
 *               template whose per-field markers bean validation
 *               replaces), app/cpy-bms/*.CPY  (the declared widths)
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
package com.cardemo.unit.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Objects;
import java.util.Set;

/**
 * The single bean-validation entry point for the DTO test tier.
 *
 * <p>Before this class existed, ten DTO test classes each answered the same question in its own way. Five
 * bootstrapped an instance {@code ValidatorFactory} in a lifecycle hook and guarded the argument; one
 * bootstrapped the same factory but did not guard; three built a throwaway factory inside every single call;
 * and one held a static factory it never used a hook for. Four implementations of one question is precisely
 * the duplication that invites divergence - a fix applied to one form silently missing the other nine.
 *
 * <p><strong>Why the parameter name is an argument rather than a constant.</strong> The guard message is
 * observable: {@code ReportRequestTest.refusesANullRequestInEveryOracle} asserts that a null argument raises
 * {@link NullPointerException} whose message contains {@code "request must not be null"}, and other classes
 * name their parameter differently - {@code CommAreaTest} used {@code "commArea must not be null"}. Hard-coding
 * a single wording here would have changed an asserted string. Passing the caller's own parameter name keeps
 * every existing contract byte-identical while the mechanism is shared.
 *
 * <p><strong>Why one factory for the whole test JVM.</strong> A {@link Validator} is immutable and
 * thread-safe once obtained, so a single instance is safe to share across classes and across the parallel-free
 * Surefire run this project uses. The factory is intentionally not closed: it lives exactly as long as the test
 * JVM, and closing it in a per-class hook is what forced ten classes to duplicate a lifecycle they did not
 * otherwise care about. The three classes that previously built a factory per call are unaffected in outcome -
 * validation is stateless - and stop paying the bootstrap cost on every assertion.
 */
public final class ValidationSupport {

    /**
     * Bootstrapped once for the test JVM. Deliberately never closed: see the class documentation.
     */
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    /** Derived from {@link #FACTORY}; immutable and thread-safe, so one instance serves every test. */
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private ValidationSupport() {
        throw new AssertionError("test support type, not instantiable");
    }

    /**
     * Returns the shared validator.
     *
     * <p>Exposed for the few assertions that need the {@link Validator} itself rather than a violation set,
     * such as validating a nested group directly or asserting that no validator is required at all.
     *
     * @return the one validator shared by the DTO test tier, never {@code null}
     */
    public static Validator validator() {
        return VALIDATOR;
    }

    /**
     * Runs a DTO through the shared validator, refusing a null argument first.
     *
     * @param <T>           the DTO type under validation
     * @param target        the DTO to validate; must not be {@code null}
     * @param parameterName the caller's own parameter name, used verbatim in the refusal message so that a
     *                      call site's asserted wording is preserved; must not be {@code null}
     * @return the constraint violations, empty when the DTO satisfies every declared constraint
     * @throws NullPointerException if {@code target} is {@code null}, with a message of the form
     *                             {@code "<parameterName> must not be null"}
     */
    public static <T> Set<ConstraintViolation<T>> violationsOf(final T target, final String parameterName) {
        Objects.requireNonNull(parameterName, "parameterName must not be null");
        return VALIDATOR.validate(Objects.requireNonNull(target, parameterName + " must not be null"));
    }
}
