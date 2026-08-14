/*
 * ******************************************************************
 * Program     : CardDemoException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception base class
 * Function    : Root of the typed hierarchy replacing COBOL FILE STATUS and CICS response-code branching.
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144,L236-L252 @ 7756d89 - the universal
 *               I/O guard idiom, and the abend exit it falls into, that this
 *               hierarchy replaces.
 * Source      : app/cpy/CSMSG02Y.cpy @ 7756d89 - the abend work areas carried by
 *               the fatal subtype of this hierarchy.
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
 * Root of the CardDemo typed exception hierarchy.
 *
 * <p>It replaces the two failure-signalling mechanisms of the frozen legacy corpus with one Java construct: the
 * {@code FILE STATUS} guard that every batch program wraps around every I/O verb, and the {@code EXEC CICS}
 * response-code branching that every online program wraps around every file request. Neither legacy mechanism
 * carries a failure value up a call chain; each aborts the unit of work at the point of detection. This class,
 * and the eight subtypes that extend it, give that abort a type, a message and a preserved root cause.
 *
 * <p>The hierarchy is closed at eight subtypes because the corpus distinguishes exactly eight failure
 * conditions: the four file-status outcomes {@code '22'}, {@code '23'}, {@code '35'} and the {@code '9x'}
 * family; the per-field validation failure the screen edits detect; the referential and domain failure the
 * relational schema now declares; the change-detection outcome of the account update; and the residual abend
 * that answers any status the source had no branch for. Every subtype preserves the causing throwable, so no
 * root cause is discarded on the way up.
 *
 * @see RuntimeException
 */
public class CardDemoException extends RuntimeException {

    /**
     * Fixed serialization identity, pinned on this class and on every subtype. {@link Throwable} already
     * implements {@link java.io.Serializable}, so this hierarchy is unavoidably serializable and the build,
     * which runs {@code -Xlint:all} with {@code -Werror}, turns the {@code serial} lint into a compilation
     * failure unless the value is declared. A fixed literal is used rather than a computed default so the
     * identity does not shift when a class is edited. No custom object-stream hook, serialization proxy or
     * externalization contract is declared anywhere in the hierarchy and none may be added: the default
     * mechanism inherited from {@link Throwable} suffices, and no instance is reconstructed from untrusted
     * input.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception that reports a failure with no underlying throwable to attribute it to.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     */
    public CardDemoException(String message) {
        super(message);
    }

    /**
     * Creates an exception that reports a failure and preserves the throwable that caused it.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public CardDemoException(String message, Throwable cause) {
        super(message, cause);
    }
}
