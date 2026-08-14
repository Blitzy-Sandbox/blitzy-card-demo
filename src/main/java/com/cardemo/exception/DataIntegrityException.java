/*
 * ******************************************************************
 * Program     : DataIntegrityException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Referential-integrity failure across the ten foreign keys of the target schema.
 * Source      : app/catlg/LISTCAT.txt @ 7756d89 - the authoritative physical
 *               specification: 10 base clusters, 3 alternate indexes with 3
 *               matching paths, and not one referential constraint among them.
 * Source      : app/cpy/CVACT03Y.cpy @ 7756d89 - the cross-reference record that
 *               makes the card to account and card to customer relationships
 *               explicit while enforcing neither of them.
 * Source      : app/cpy/CVTRA01Y.cpy @ 7756d89 - the composite category-balance
 *               key, whose three parts each point at a different parent record.
 * Source      : app/cpy/CVTRA05Y.cpy @ 7756d89 - the 350-byte transaction record,
 *               which carries the card number it relates to as ordinary data.
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
 * Reports that a write violated a referential or check constraint declared by the target schema.
 *
 * <p>It gives a name to one specific failure: a row could not be written because the relational store refused
 * it, either because a parent row the write depends on does not exist or because a value fell outside a
 * declared domain. It carries an opaque, caller-supplied constraint identifier, the relation the write was
 * aimed at, and - most importantly - the underlying throwable that actually knows what went wrong.
 *
 * <p>The legacy corpus had no equivalent: VSAM enforced no referential rule, so the checks this type reports
 * on are ones the relational schema declares - ten foreign keys and five check constraints - to hold invariants
 * the COBOL held only by convention. A violation is therefore always a defect in the caller or in the data it
 * was handed, never a condition to be retried, and this type neither classifies the constraint nor decides a
 * response: it names the relation, passes the store's own identifier through opaquely, and preserves the cause.
 *
 * @see CardDemoException
 */
public class DataIntegrityException extends CardDemoException {

    /**
     * Fixed serialization identity, on the contract described on {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The identifier of the violated constraint, exactly as supplied by the caller, or null when the caller did
     * not name one.
     */
    private final String constraintName;

    /**
     * The relation the refused write was aimed at - conventionally a table name - exactly as supplied by the
     * caller, or null when the caller did not name one.
     */
    private final String relation;

    /**
     * Creates an integrity failure that reports a refused write with no underlying throwable and no constraint
     * identity.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     */
    public DataIntegrityException(String message) {
        super(message);
        this.constraintName = null;
        this.relation = null;
    }

    /**
     * Creates an integrity failure that preserves the throwable the store raised, without naming a constraint.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable, typically the store-specific constraint-violation exception,
     * retrievable through {@link Throwable#getCause()}.
     */
    public DataIntegrityException(String message, Throwable cause) {
        super(message, cause);
        this.constraintName = null;
        this.relation = null;
    }

    /**
     * Creates an integrity failure that names the violated constraint and the relation, with no underlying
     * throwable.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param constraintName the identifier of the violated constraint.
     * @param relation the relation the refused write was aimed at, conventionally a table name.
     */
    public DataIntegrityException(String message, String constraintName, String relation) {
        super(message);
        this.constraintName = constraintName;
        this.relation = relation;
    }

    /**
     * Creates a fully populated integrity failure: message, constraint identity, relation and preserved cause.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param constraintName the identifier of the violated constraint.
     * @param relation the relation the refused write was aimed at, conventionally a table name.
     * @param cause the underlying throwable, typically the store-specific constraint-violation exception,
     * retrievable through {@link Throwable#getCause()}.
     */
    public DataIntegrityException(String message, String constraintName, String relation, Throwable cause) {
        super(message, cause);
        this.constraintName = constraintName;
        this.relation = relation;
    }

    /**
     * Returns the identifier of the violated constraint, exactly as it was supplied at construction.
     *
     * @return the constraint identifier as supplied, which may be null when no identifier was given and may be
     * blank when a blank one was given.
     */
    public String getConstraintName() {
        return constraintName;
    }

    /**
     * Returns the relation the refused write was aimed at, exactly as it was supplied at construction.
     *
     * @return the relation name as supplied, which may be null when no relation was given and may be blank when
     * a blank one was given.
     */
    public String getRelation() {
        return relation;
    }
}
