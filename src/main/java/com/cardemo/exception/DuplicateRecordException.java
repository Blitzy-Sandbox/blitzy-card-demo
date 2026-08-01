/*
 * ******************************************************************
 * Program     : DuplicateRecordException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed translation of FILE STATUS '22' / DFHRESP(DUPREC) / DFHRESP(DUPKEY) - duplicate key.
 * Source      : app/cbl/COUSR01C.cbl:L260-L261 @ 7756d89 - the canonical
 *               DFHRESP(DUPKEY) site, user add against the eight byte USRSEC key.
 * Source      : app/cbl/COTRN02C.cbl:L735-L736 @ 7756d89 - transaction add.
 * Source      : app/cbl/COBIL00C.cbl:L533-L534 @ 7756d89 - bill payment.
 * Source      : app/jcl/COMBTRAN.jcl:STEP10 @ 7756d89 - the IDCAMS REPRO bulk
 *               load where duplicate interest identifiers surface.
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
 * Signals that a keyed write collided with a record that already exists.
 *
 * <p>It is the typed target for the duplicate key outcome of the frozen legacy corpus: {@code FILE STATUS '22'}
 * in the batch idiom, and {@code DFHRESP(DUPREC)} together with {@code DFHRESP(DUPKEY)} in the online programs.
 * It is thrown when an insert, a write or a bulk load is rejected because the key is already present in the
 * store.
 *
 * <p>This class is not defensive decoration. It is the designed <em>surfacing mechanism</em> for two hazards
 * that the migration preserves on purpose rather than repairs. The first is the maximum-plus-one identifier
 * generation of {@code app/cbl/COTRN02C.cbl:444-451} and {@code app/cbl/COBIL00C.cbl:212-219}, which is
 * inherently racy exactly as the descending browse was, and which must keep surfacing a collision here rather
 * than being replaced by a database sequence. The second is the combine job's bulk load, where a repeated date
 * parameter produces colliding interest-transaction identifiers that must fail the step rather than becoming a
 * silent upsert. Papering over either one changes generated identifier values and breaks the boundary parity
 * comparison the migration is accepted against.
 *
 * @see CardDemoException
 */
public class DuplicateRecordException extends CardDemoException {

    /**
     * Fixed serialization identity, on the contract described on {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The logical file the colliding write was aimed at, or null when the caller did not identify one.
     */
    private final String logicalFile;

    /**
     * The key value that already existed, or null when the caller did not supply it.
     */
    private final String collidingKey;

    /**
     * Creates a duplicate key failure that identifies neither the logical file nor the colliding key.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     */
    public DuplicateRecordException(String message) {
        this(message, null, null);
    }

    /**
     * Creates a duplicate key failure that preserves the throwable that reported the collision.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public DuplicateRecordException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    /**
     * Creates a duplicate key failure that identifies the logical file and the colliding key.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param logicalFile the logical file the write was aimed at, such as {@code USRSEC} or {@code TRANSACT}.
     * @param collidingKey the key value that already existed.
     */
    public DuplicateRecordException(String message, String logicalFile, String collidingKey) {
        super(message);
        this.logicalFile = logicalFile;
        this.collidingKey = collidingKey;
    }

    /**
     * Creates a fully described duplicate key failure that also preserves its root cause.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param logicalFile the logical file the write was aimed at, such as {@code USRSEC} or {@code TRANSACT}.
     * @param collidingKey the key value that already existed.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public DuplicateRecordException(String message, String logicalFile, String collidingKey,
            Throwable cause) {
        super(message, cause);
        this.logicalFile = logicalFile;
        this.collidingKey = collidingKey;
    }

    /**
     * Returns the logical file the colliding write was aimed at.
     *
     * @return the legacy logical file name, such as {@code USRSEC} or {@code TRANSACT}, or null when the
     * throwing site did not identify one.
     */
    public String getLogicalFile() {
        return logicalFile;
    }

    /**
     * Returns the key value that already existed.
     *
     * @return the colliding key, never the colliding record, or null when the throwing site did not supply it.
     */
    public String getCollidingKey() {
        return collidingKey;
    }
}
