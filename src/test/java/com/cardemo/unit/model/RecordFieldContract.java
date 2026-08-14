/*
 * ******************************************************************
 * Program     : RecordFieldContract.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Reads the declared bean-validation contract off a DTO
 *               record component by reflection, so that a test can
 *               compare a declared @Size ceiling against the copybook
 *               width it is supposed to reproduce, and can assert the
 *               deliberate ABSENCE of a constraint the source never
 *               performed.
 * Source      : app/cpy-bms/*.CPY  (the widths being reproduced)
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

import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Reads the declared validation contract off a record component.
 *
 * <p>An annotation written on a record component is propagated by the compiler to whichever of the record
 * component, the backing field, the accessor method and the constructor parameter the annotation's
 * {@code @Target} permits. Which of those actually carries it is a detail of the annotation's own
 * declaration, so every lookup here tries the record component, then the field, then the accessor, and uses
 * the first that answers. A test that reached for only one of the three would report a constraint as absent
 * when it is merely declared somewhere else.
 *
 * <p>This class is shared deliberately. Four DTO test classes need the same two questions answered - what
 * ceiling does this component declare, and is this constraint deliberately absent - and answering them in
 * one place keeps a single definition of how the question is asked.
 */
final class RecordFieldContract {

    private RecordFieldContract() {
        throw new AssertionError("test support type, not instantiable");
    }

    /**
     * Returns the component names of a record, in declaration order.
     *
     * @param recordType the record class
     * @return the component names in declaration order
     */
    static List<String> componentNames(final Class<?> recordType) {
        return Arrays.stream(requireRecord(recordType).getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the {@code max} of the {@link Size} constraint declared on one component.
     *
     * @param recordType the record class
     * @param component  the component name
     * @return the declared maximum length
     * @throws AssertionError if the component declares no {@code @Size}, which is a finding rather than a
     *                        test defect
     */
    static int declaredSizeMax(final Class<?> recordType, final String component) {
        final Size size = annotationOn(recordType, component, Size.class);
        if (size == null) {
            throw new AssertionError(recordType.getSimpleName() + "." + component
                    + " declares no @Size constraint, so it has no width ceiling to compare");
        }
        return size.max();
    }

    /**
     * Reports whether a component declares a given constraint.
     *
     * <p>Used to assert deliberate absence as well as presence. Several DTOs in this package document that
     * they intentionally declare no digits-only or non-null constraint, because the legacy program performed
     * no such check at the screen boundary and inventing one would reject input the source accepted.
     *
     * @param recordType     the record class
     * @param component      the component name
     * @param annotationType the constraint to look for
     * @return {@code true} when the component declares it
     */
    static boolean declares(final Class<?> recordType, final String component,
            final Class<? extends Annotation> annotationType) {
        return annotationOn(recordType, component, annotationType) != null;
    }

    /**
     * Finds an annotation on a record component, wherever the compiler propagated it.
     *
     * @param recordType     the record class
     * @param component      the component name
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the annotation, or {@code null} when the component declares none
     */
    static <A extends Annotation> A annotationOn(final Class<?> recordType, final String component,
            final Class<A> annotationType) {
        requireRecord(recordType);
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(annotationType, "annotationType");

        final RecordComponent declared = Arrays.stream(recordType.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError(recordType.getSimpleName()
                        + " declares no component named " + component + "; it declares "
                        + componentNames(recordType)));

        final A onComponent = declared.getAnnotation(annotationType);
        if (onComponent != null) {
            return onComponent;
        }
        try {
            final Field field = recordType.getDeclaredField(component);
            final A onField = field.getAnnotation(annotationType);
            if (onField != null) {
                return onField;
            }
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError("a record component always has a backing field: " + component, absent);
        }
        try {
            final Method accessor = recordType.getDeclaredMethod(component);
            return accessor.getAnnotation(annotationType);
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError("a record component always has an accessor: " + component, absent);
        }
    }

    /**
     * Folds a flattened screen-field-to-record-component list into one {@code field:component} pair
     * per entry.
     *
     * <p>Four DTO test classes each declare a parameterised test that walks the mapping between a
     * BMS screen field and the record component carrying it, and each supplies that mapping as a
     * flat list of alternating names so the pairing is written once per DTO rather than twice. The
     * folding is identical in every one of them, so it lives here; the flat list stays with its
     * owner because it <em>is</em> that DTO's field contract.</p>
     *
     * <p>The odd-length refusal matters more than it appears. A flat list is exactly the shape in
     * which a dropped entry is invisible: losing one name silently re-pairs every subsequent
     * field with the wrong component, and each resulting pair still looks well formed. Refusing an
     * odd length turns that silent mis-pairing into an immediate failure.</p>
     *
     * @param flattened alternating screen-field and record-component names; must not be
     *                  {@code null} and must have an even length
     * @return one {@code field:component} string per pair, in declaration order
     * @throws NullPointerException if {@code flattened} is {@code null}
     * @throws AssertionError       if {@code flattened} has an odd number of entries
     */
    static List<String> pairsOf(final List<String> flattened) {
        Objects.requireNonNull(flattened, "flattened");
        if (flattened.size() % 2 != 0) {
            throw new AssertionError("the flattened field-to-component list must pair every field "
                    + "with a component, but it holds an odd number of entries: " + flattened.size());
        }
        return java.util.stream.IntStream.range(0, flattened.size() / 2)
                .mapToObj(index -> flattened.get(2 * index) + ":" + flattened.get(2 * index + 1))
                .toList();
    }

    private static Class<?> requireRecord(final Class<?> recordType) {
        Objects.requireNonNull(recordType, "recordType");
        if (!recordType.isRecord()) {
            throw new AssertionError(recordType.getName() + " is not a record");
        }
        return recordType;
    }
}
