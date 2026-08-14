/*
 * ******************************************************************
 * Program     : ValidationException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Field-level validation failure, replacing the CSSETATY per-field error-marker template.
 * Source      : app/cpy/CSSETATY.cpy @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L510-L528 @ 7756d89
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
package com.cardemo.exception;

/**
 * A validation failure that identifies the input that was rejected and how it was rejected.
 *
 * <p>It is the typed replacement for the per-field error markers that the frozen legacy corpus produced from
 * one parameterised copybook, {@code app/cpy/CSSETATY.cpy}. That copybook is the single member of this
 * exception package's source set whose content is <em>procedural</em> rather than a record layout: it is a
 * {@code PROCEDURE DIVISION} fragment resolved by {@code COPY ... REPLACING} at each use site, so it has no
 * entity and no data transfer object counterpart and contributes no import anywhere in the Java tree. What it
 * contributes instead is a <em>contract</em>: after a screen's edits have run, every field that failed is
 * marked, and a field that failed because it was left empty is marked differently from a field that failed
 * because the value supplied was wrong. This class carries exactly that pair of facts - which field, and which
 * of the two failure kinds - and nothing else.
 *
 * <p>The two failure kinds are not interchangeable, because the source distinguishes them: a field left empty
 * and a field supplied with an unacceptable value receive different markers and different screen messages. A
 * caller that collapses them loses information the legacy screen showed. This type carries no message text and
 * no ordering: the precedence in which several failures are reported to a user belongs to the service that ran
 * the edits, exactly as the source's screen-message staging did.
 *
 * @see CardDemoException
 */
public class ValidationException extends CardDemoException {

    /**
     * Fixed serialization identity, on the contract described on {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The two ways a single input can fail, transcribed from the outer and inner conditions of
     * {@code app/cpy/CSSETATY.cpy:L18-L26}.
     */
    public enum FailureKind {

        /**
         * A value was supplied and it is wrong: the transcription of {@code FLG-(TESTVAR1)-NOT-OK} at
         * {@code app/cpy/CSSETATY.cpy:L18}.
         */
        INVALID,

        /**
         * A required value was not supplied at all: the transcription of {@code FLG-(TESTVAR1)-BLANK} at
         * {@code app/cpy/CSSETATY.cpy:L19}, the condition that the template's inner {@code IF} at L23 singles
         * out in order to write {@code '*'} into the field.
         */
        BLANK
    }

    /**
     * The name of the input this failure attaches to, or null when no single input owns it. Declared at
     * {@code app/cpy/CSSETATY.cpy:L22}.
     */
    private final String fieldName;

    /**
     * Which of the two failure conditions of {@code app/cpy/CSSETATY.cpy:L18-L19} applies. Never null: every
     * constructor either receives a non-null value or substitutes {@link FailureKind#INVALID}.
     */
    private final FailureKind failureKind;

    /**
     * Creates a request level validation failure with no field identity and no underlying throwable.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     */
    public ValidationException(String message) {
        super(message);
        this.fieldName = null;
        this.failureKind = FailureKind.INVALID;
    }

    /**
     * Creates a request level validation failure that preserves the throwable which caused it.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldName = null;
        this.failureKind = FailureKind.INVALID;
    }

    /**
     * Creates a validation failure for a named input, with no underlying throwable.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param fieldName the name of the offending input, retrievable through {@link #getFieldName()}.
     * @param failureKind which of the two failure conditions applies.
     */
    public ValidationException(String message, String fieldName, FailureKind failureKind) {
        super(message);
        this.fieldName = fieldName;
        this.failureKind = failureKind == null ? FailureKind.INVALID : failureKind;
    }

    /**
     * Creates a validation failure for a named input and preserves the throwable which caused it.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param fieldName the name of the offending input, retrievable through {@link #getFieldName()}.
     * @param failureKind which of the two failure conditions applies.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public ValidationException(String message, String fieldName, FailureKind failureKind,
            Throwable cause) {
        super(message, cause);
        this.fieldName = fieldName;
        this.failureKind = failureKind == null ? FailureKind.INVALID : failureKind;
    }

    /**
     * Reports that a named input was supplied and is wrong.
     *
     * @param fieldName the name of the offending input.
     * @param message the detail message. Permitted to be null or blank and passed through unchanged.
     * @return a new exception carrying the supplied name with {@link FailureKind#INVALID}.
     */
    public static ValidationException invalidField(String fieldName, String message) {
        return new ValidationException(message, fieldName, FailureKind.INVALID);
    }

    /**
     * Reports that a required input was not supplied at all.
     *
     * @param fieldName the name of the absent input.
     * @param message the detail message. Permitted to be null or blank and passed through unchanged.
     * @return a new exception carrying the supplied name with {@link FailureKind#BLANK}.
     */
    public static ValidationException missingField(String fieldName, String message) {
        return new ValidationException(message, fieldName, FailureKind.BLANK);
    }

    /**
     * Returns the name of the input this failure attaches to.
     *
     * @return the field name, or null when the failure is request level.
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Returns which of the two failure conditions applies, distinguishing a wrong value from an absent one.
     *
     * @return the failure kind; never null
     */
    public FailureKind getFailureKind() {
        return failureKind;
    }

    /**
     * Reports whether this failure identifies a specific input.
     *
     * @return true when a usable field name is present, false when the name is null or blank and the failure is
     * therefore request level
     */
    public boolean hasFieldName() {
        return fieldName != null && !fieldName.isBlank();
    }
}
