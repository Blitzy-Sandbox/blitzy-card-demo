/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.exception;

/**
 * Thrown when an optimistic-locking conflict is detected while updating a
 * persistent record, indicating the record was changed by another user
 * between the read and the update.
 *
 * <p>The exception extends {@link java.lang.RuntimeException} directly and
 * carries no dependency on any persistence, web, or application package.
 * Callers wrap persistence-layer optimistic-lock failures into this type and
 * may supply the underlying cause through the cause-accepting constructors.
 */
public class ConcurrentUpdateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Default detail message describing a concurrent-update conflict.
     */
    public static final String DEFAULT_MESSAGE =
            "Record changed by some one else. Please review";

    /**
     * Name of the record type that conflicted (for example {@code "Account"},
     * {@code "Card"}, or {@code "Customer"}); {@code null} when not specified.
     */
    private final String entityType;

    /**
     * Creates an exception using {@link #DEFAULT_MESSAGE} and no entity type.
     */
    public ConcurrentUpdateException() {
        super(DEFAULT_MESSAGE);
        this.entityType = null;
    }

    /**
     * Creates an exception with the supplied detail message and no entity type.
     *
     * @param message the detail message
     */
    public ConcurrentUpdateException(String message) {
        super(message);
        this.entityType = null;
    }

    /**
     * Creates an exception with the supplied detail message and cause and no
     * entity type.
     *
     * @param message the detail message
     * @param cause   the underlying cause (may be {@code null})
     */
    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
        this.entityType = null;
    }

    /**
     * Creates an exception that wraps the supplied cause while reporting
     * {@link #DEFAULT_MESSAGE}, with no entity type.
     *
     * @param cause the underlying cause (may be {@code null})
     */
    public ConcurrentUpdateException(Throwable cause) {
        super(DEFAULT_MESSAGE, cause);
        this.entityType = null;
    }

    /**
     * Canonical constructor used by the factory methods to populate every
     * field.
     *
     * @param message    the detail message
     * @param cause      the underlying cause (may be {@code null})
     * @param entityType the conflicting record type (may be {@code null})
     */
    private ConcurrentUpdateException(String message, Throwable cause, String entityType) {
        super(message, cause);
        this.entityType = entityType;
    }

    /**
     * Creates an exception that wraps the supplied cause and records the
     * conflicting entity type, reporting {@link #DEFAULT_MESSAGE}.
     *
     * @param entityType the conflicting record type (for example
     *                   {@code "Account"}, {@code "Card"}, or
     *                   {@code "Customer"}); may be {@code null}
     * @param cause      the underlying cause (may be {@code null})
     * @return a new {@code ConcurrentUpdateException} carrying the entity type
     */
    public static ConcurrentUpdateException forEntity(String entityType, Throwable cause) {
        return new ConcurrentUpdateException(DEFAULT_MESSAGE, cause, entityType);
    }

    /**
     * Returns the name of the record type that conflicted, if known.
     *
     * @return the conflicting record type, or {@code null} when not specified
     */
    public String getEntityType() {
        return entityType;
    }
}
