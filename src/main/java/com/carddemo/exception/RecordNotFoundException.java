/*
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
 * language governing permissions and limitations under the License.
 */
package com.carddemo.exception;

/**
 * Signals that a requested record could not be located.
 *
 * <p>Optionally carries the entity type that was requested and the key that was
 * used for the lookup. Both context fields are nullable and are stored as
 * {@link String} values so that instances remain fully serializable.
 */
public class RecordNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String entityType;

    private final String key;

    /**
     * Creates an exception with the supplied detail message.
     *
     * @param message the detail message
     */
    public RecordNotFoundException(String message) {
        super(message);
        this.entityType = null;
        this.key = null;
    }

    /**
     * Creates an exception with the supplied detail message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause, which may be {@code null}
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.entityType = null;
        this.key = null;
    }

    /**
     * Creates an exception describing which entity was not found and the key
     * used for the lookup. The detail message is derived deterministically as
     * {@code entityType + " not found: " + key}.
     *
     * @param entityType the type of entity that was requested
     * @param key        the key used for the lookup
     */
    public RecordNotFoundException(String entityType, Object key) {
        super(entityType + " not found: " + key);
        this.entityType = entityType;
        this.key = String.valueOf(key);
    }

    /**
     * Returns the entity type that was requested, or {@code null} when not set.
     *
     * @return the entity type, or {@code null}
     */
    public String getEntityType() {
        return entityType;
    }

    /**
     * Returns the key used for the lookup, or {@code null} when not set.
     *
     * @return the key, or {@code null}
     */
    public String getKey() {
        return key;
    }
}
