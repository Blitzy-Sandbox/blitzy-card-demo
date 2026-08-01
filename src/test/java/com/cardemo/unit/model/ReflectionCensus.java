/*
 * ******************************************************************
 * Program     : ReflectionCensus.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Owns the declared-member censuses that the structural
 *               test classes use to assert a type's published surface.
 *               Three classes previously each declared their own
 *               declaredMethodNames, and two each declared their own
 *               declaredFieldNames, declaredSurfaceTypeNames and
 *               declaredAnnotationTypeNames, with filters that quietly
 *               disagreed; the iteration now lives here once so a
 *               census cannot mean different things in different files.
 * Source      : app/cpy-bms/*.CPY and app/cpy/*.cpy  (the frozen field
 *               contracts whose Java surface these censuses police)
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The declared-member censuses shared by the structural test tier.
 *
 * <p>These censuses answer the question "what does this type actually publish?" so that a test can
 * assert the published surface rather than restate it. Because the answer is used to prove that a
 * type publishes <em>nothing beyond</em> its field contract, a census that silently omits a member
 * is the single most dangerous defect such a helper can carry: the assertion still passes, and the
 * omission is invisible. That is precisely the failure mode this consolidation removes.</p>
 *
 * <h2>Why these were consolidated, and why it is behaviour-preserving</h2>
 *
 * <p>Three owners each declared a {@code declaredMethodNames} with a different filter, and the
 * difference was not cosmetic:</p>
 *
 * <ul>
 *   <li>one filtered synthetic <em>and</em> bridge methods;</li>
 *   <li>one filtered synthetic methods only;</li>
 *   <li>one filtered nothing and sorted the result.</li>
 * </ul>
 *
 * <p>Consolidating divergent filters is only safe if the divergence is provably inert on the types
 * the helpers are actually applied to, so that was measured rather than assumed. Across every type
 * any of the three owners inspects &mdash; {@code MenuResponse} with its four nested types,
 * {@code PageResponse}, and all eleven repository interfaces &mdash; the bridge-method count is
 * <strong>zero</strong>, and the only synthetic method is the one the {@code MenuType} enum
 * declares, which every owner already filtered. The bridge filter is therefore a no-op today and is
 * retained as a hardening: it makes a future covariant override impossible to miscount rather than
 * merely absent, the same reasoning applied to the identifier boundary in the schema parser.</p>
 *
 * <p>The sorted contract is a genuinely different one and is <em>not</em> absorbed here. Its owner
 * keeps its own ordering by sorting this census's result, which removes the duplicated iteration
 * without changing what that owner asserts.</p>
 *
 * <h2>What a census deliberately does not do</h2>
 *
 * <p>No census filters by visibility, and none walks a supertype. A structural assertion about a
 * type's own contract must see the private members too &mdash; that is how the guard helpers added
 * for the width-and-domain work are proved to exist &mdash; and inherited members belong to the
 * supertype's contract, not to this one.</p>
 *
 * @see RecordFieldContract
 * @see ValidationSupport
 */
public final class ReflectionCensus {

    /**
     * Prevents instantiation. Every member is static.
     */
    private ReflectionCensus() {
        throw new AssertionError("ReflectionCensus is a static holder and must not be instantiated");
    }

    /**
     * Names every method the type declares itself, excluding compiler-generated members.
     *
     * <p>Synthetic and bridge methods are excluded because neither is part of the surface a field
     * contract describes: both are artefacts of compilation rather than declarations an author
     * made. Overloads are reported once per declaration, so a name may repeat.</p>
     *
     * @param type the type to inspect; must not be {@code null}
     * @return the declared method names in reflective order, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static List<String> declaredMethodNames(final Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        final List<String> names = new ArrayList<>();
        for (final Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic() && !method.isBridge()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * Names every field the type declares itself, selected by storage class.
     *
     * <p>Splitting on {@code static} is what lets a caller assert the instance state separately
     * from the declared constants, which are two different contracts: the first must match the
     * copybook record layout, the second must not silently grow.</p>
     *
     * @param type       the type to inspect; must not be {@code null}
     * @param wantStatic {@code true} to name the static fields, {@code false} for the instance
     *                   fields
     * @return the matching declared field names in reflective order, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static List<String> declaredFieldNames(final Class<?> type, final boolean wantStatic) {
        Objects.requireNonNull(type, "type must not be null");
        final List<String> names = new ArrayList<>();
        for (final Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && Modifier.isStatic(field.getModifiers()) == wantStatic) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /**
     * Names every type that appears anywhere in the type's own declared surface.
     *
     * <p>Field types, method return types, method parameter types and constructor parameter types
     * are all reported, each as its generic type name so that a parameterised type is
     * distinguishable from its erasure. This is the census that proves a DTO exposes no
     * floating-point type and no mutable collection implementation, which is why it reads generic
     * names rather than raw ones.</p>
     *
     * @param type the type to inspect; must not be {@code null}
     * @return every declared type name, with repeats, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static List<String> declaredSurfaceTypeNames(final Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        final List<String> names = new ArrayList<>();
        for (final Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                names.add(field.getGenericType().getTypeName());
            }
        }
        for (final Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic() && !method.isBridge()) {
                names.add(method.getGenericReturnType().getTypeName());
                for (final Type parameter : method.getGenericParameterTypes()) {
                    names.add(parameter.getTypeName());
                }
            }
        }
        for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) {
                for (final Type parameter : constructor.getGenericParameterTypes()) {
                    names.add(parameter.getTypeName());
                }
            }
        }
        return names;
    }

    /**
     * Names every annotation declared on the type or on any member it declares.
     *
     * <p>The type itself, its fields, its methods, its constructors and &mdash; when it is a record
     * &mdash; its record components are all read. Record components are read explicitly because an
     * annotation written on a record component is propagated to the field, the accessor and the
     * constructor parameter according to that annotation's own targets, so a census that skipped
     * the component could miss a declaration that no other reflective view reports.</p>
     *
     * @param type the type to inspect; must not be {@code null}
     * @return every declared annotation type name, with repeats, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static List<String> declaredAnnotationTypeNames(final Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        final List<String> names = new ArrayList<>();
        for (final Annotation annotation : type.getDeclaredAnnotations()) {
            names.add(annotation.annotationType().getName());
        }
        for (final Field field : type.getDeclaredFields()) {
            for (final Annotation annotation : field.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        for (final Method method : type.getDeclaredMethods()) {
            for (final Annotation annotation : method.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (final Annotation annotation : constructor.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        final RecordComponent[] components = type.getRecordComponents();
        if (components != null) {
            for (final RecordComponent component : components) {
                for (final Annotation annotation : component.getDeclaredAnnotations()) {
                    names.add(annotation.annotationType().getName());
                }
            }
        }
        return names;
    }
}
