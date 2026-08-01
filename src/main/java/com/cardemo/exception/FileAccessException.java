/*
 * ******************************************************************
 * Program     : FileAccessException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed translation of the FILE STATUS '9x' family, carrying the four-character expanded status.
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144 @ 7756d89 - IO-STATUS, TWO-BYTES-BINARY
 *               and its TWO-BYTES-ALPHA redefinition, IO-STATUS-04 and APPL-RESULT:
 *               the declarations that fix the rendered width at four characters.
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89 - 9910-DISPLAY-IO-STATUS, the
 *               two branch four character renderer whose output this type carries.
 * Source      : app/cbl/CBTRN02C.cbl:L236-L252 @ 7756d89 - the universal I/O guard
 *               idiom, which renders the status and only then abends, and which is the
 *               site this type is raised from.
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

import com.cardemo.model.enums.FileStatus;

/**
 * Signals a physical or logical I/O failure - the COBOL {@code FILE STATUS} {@code '9x'} family - and carries
 * the four character expanded status that the legacy operator read off the job log.
 *
 * <p>In the frozen corpus a failing I/O verb does not return a value to a caller. It renders a diagnostic and
 * terminates the run. This type is the Java form of the diagnostic half of that pair: it names the failure,
 * identifies the file and the operation that provoked it, and transports the exact four characters the legacy
 * renderer produced so that the logging layer can emit a line which matches the legacy baseline byte for byte.
 * It is the only member of the hierarchy that carries a rendered status, because {@code '9x'} is the only
 * family whose second byte is a binary subcode rather than a displayable digit.
 *
 * @see CardDemoException
 * @see FatalProcessingException
 */
public class FileAccessException extends CardDemoException {

    /**
     * Fixed serialization identity, on the contract described on {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The four character expanded status, obtained once at construction from the single owning renderer.
     */
    private final String expandedStatus;

    /**
     * The logical file or DD name whose I/O failed, for example {@code DALYTRAN}, {@code ACCTDAT},
     * {@code TRANSACT} or {@code STMTFILE}. May be {@code null} when the caller did not identify it.
     */
    private final String logicalFileName;

    /**
     * The attempted operation, for example {@code OPEN}, {@code READ}, {@code WRITE}, {@code REWRITE},
     * {@code DELETE} or {@code CLOSE}. May be {@code null} when the caller did not identify it.
     */
    private final String operation;

    /**
     * Creates an I/O failure with a message only, recording no status, file name, operation or cause.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     */
    public FileAccessException(String message) {
        this(message, null, null, null, null);
    }

    /**
     * Creates an I/O failure with a message and the underlying throwable that caused it.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public FileAccessException(String message, Throwable cause) {
        this(message, null, null, null, cause);
    }

    /**
     * Creates an I/O failure that records the raw status, the file and the operation, with no cause.
     *
     * @param message the detail message, subject to the prohibition on secrets, personally identifiable values
     * and record images.
     * @param ioStatus the raw two character file status, for example {@code "90"}.
     * @param logicalFileName the logical file or DD name that failed, for example {@code "DALYTRAN"}.
     * @param operation the attempted operation, for example {@code "OPEN"}.
     */
    public FileAccessException(String message, String ioStatus, String logicalFileName, String operation) {
        this(message, ioStatus, logicalFileName, operation, null);
    }

    /**
     * Creates an I/O failure that records the raw status, the file, the operation and the cause.
     *
     * <p>This is the canonical constructor; every other constructor on this class delegates to it, so the
     * fields are assigned in exactly one place. It is the preferred form at any site that has both a
     * status and a throwable, because it loses neither.
     *
     * <p>The raw status is expanded exactly once, here, by the single owning renderer, and the result is
     * stored. Concatenating the owning twenty character prefix constant with the stored value reproduces
     * the legacy line byte for byte - see the class documentation for the worked examples and for the
     * placeholder quirk that makes the line for status {@code '23'} read {@code FILE STATUS IS: NNNN0023}.
     *
     * <p><strong>The renderer used here is the log safe one.</strong> An exception is a diagnostic object:
     * everything it carries is destined for a log record, and a status that arrived malformed from a store
     * or adapter layer this corpus does not control could otherwise put a raw control byte there, splitting
     * the record or forging a second one. {@code FileStatus.renderIoStatus04ForDiagnostics(String)}
     * encodes anything that is not printable ASCII, and for every status the corpus actually produces -
     * {@code '00'}, {@code '04'}, {@code '10'}, {@code '22'}, {@code '23'}, {@code '35'} and the
     * {@code '9'} family - its output is character for character identical to the parity renderer's. The
     * byte for byte reproduction described above therefore still holds for every legitimate status, and
     * diverges only where the alternative would have been an unusable log record. The one site that must
     * emit the unencoded parity line is {@code FileStatusMapper.displayIoStatus(String)}, which renders it
     * directly from the raw status and does not read this field.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded, no
     * file handle, channel or path is retained, and no state outside this instance is read or written.
     *
     * <p>Error modes: none - this constructor cannot fail, for the reasons given on
     * {@link #FileAccessException(String, String, String, String)}.
     *
     * @param message         the detail message, subject to the prohibition on secrets, personally
     *                        identifiable values and record images. Permitted to be {@code null} or blank
     * @param ioStatus        the raw two character file status, for example {@code "90"}. Permitted to be
     *                        {@code null}, shorter or longer, and normalised rather than rejected
     * @param logicalFileName the logical file or DD name that failed, for example {@code "DALYTRAN"}.
     * @param operation the attempted operation, for example {@code "OPEN"}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public FileAccessException(String message, String ioStatus, String logicalFileName, String operation,
            Throwable cause) {
        super(message, cause);
        this.expandedStatus = FileStatus.renderIoStatus04ForDiagnostics(ioStatus);
        this.logicalFileName = logicalFileName;
        this.operation = operation;
    }

    /**
     * Returns the four character expanded status this failure carries.
     *
     * <p>To reproduce the legacy diagnostic line, concatenate the twenty character prefix constant owned
     * by {@code FileStatus} with this value and place nothing whatsoever between them. Do not substitute
     * this value into the {@code NNNN} text inside that prefix; the placeholder stays and the value is
     * appended after it, which is what the corpus does at {@code app/cbl/CBTRN02C.cbl:L721} and
     * {@code app/cbl/CBTRN02C.cbl:L725}.
     *
     * <p>Side effects: none. The value was rendered once at construction and is returned as stored, so no
     * formatting occurs here and repeated calls are free and identical.
     *
     * @return the expanded status, never {@code null} and containing printable ASCII only. Exactly four
     *         characters for every status the corpus produces, and longer only when a malformed byte had to
     *         be encoded. For an instance built without a status this is {@code " 032"}, the faithful
     *         rendering of an uninitialised two byte status field
     */
    public String getExpandedStatus() {
        return expandedStatus;
    }

    /**
     * Returns the logical file or DD name whose I/O failed.
     *
     * @return the logical file name, for example {@code "DALYTRAN"} or {@code "ACCTDAT"}, or {@code null} when
     * the throwing site did not identify one.
     */
    public String getLogicalFileName() {
        return logicalFileName;
    }

    /**
     * Returns the operation that was attempted when the I/O failed.
     *
     * @return the operation, for example {@code "OPEN"} or {@code "READ"}, or {@code null} when the throwing
     * site did not identify one.
     */
    public String getOperation() {
        return operation;
    }
}
