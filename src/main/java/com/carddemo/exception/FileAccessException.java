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
 * Signals an unexpected data-access, logic, or I/O error encountered while
 * reading from or writing to a persistent store.
 *
 * <p>This is the translation target for the legacy VSAM {@code FILE STATUS}
 * {@code 9x} family (implementation-defined logic errors) and for any
 * non-success, non-end-of-file status that the batch programs treat as an
 * unrecoverable I/O failure. Service and batch components also raise it when
 * wrapping an unexpected persistence failure (for example a JDBC or JPA error),
 * so that a single typed condition represents every unexpected data-access
 * fault.
 *
 * <p>The exception extends {@link java.lang.RuntimeException} directly and
 * carries no dependency on any persistence, web, or application package. It may
 * optionally carry two nullable diagnostic fields, {@link #getOperation()} and
 * {@link #getFileStatus()}, that describe the failing operation and the
 * originating status code. These fields are intended for server-side logging
 * only and are never surfaced to remote callers.
 */
public class FileAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Name of the failing operation (for example {@code "READ"},
     * {@code "REWRITE"}, or {@code "OPEN ACCTFILE"}); {@code null} when not
     * specified.
     */
    private final String operation;

    /**
     * Originating two-character VSAM file-status code or its mapped equivalent
     * (for example {@code "92"}); {@code null} when not specified.
     */
    private final String fileStatus;

    /**
     * Creates an exception with the supplied detail message and no diagnostic
     * context.
     *
     * @param message the detail message
     */
    public FileAccessException(String message) {
        super(message);
        this.operation = null;
        this.fileStatus = null;
    }

    /**
     * Creates an exception with the supplied detail message and cause and no
     * diagnostic context. This is the primary form used to wrap a caught
     * persistence-layer failure.
     *
     * @param message the detail message
     * @param cause   the underlying cause, which may be {@code null}
     */
    public FileAccessException(String message, Throwable cause) {
        super(message, cause);
        this.operation = null;
        this.fileStatus = null;
    }

    /**
     * Creates an exception describing the failing operation and its originating
     * status code. The detail message is derived deterministically as
     * {@code "Data access error during " + operation + " (status " + fileStatus + ")"}.
     *
     * @param operation  the name of the failing operation
     * @param fileStatus the originating status code
     * @param cause      the underlying cause, which may be {@code null}
     */
    public FileAccessException(String operation, String fileStatus, Throwable cause) {
        super("Data access error during " + operation + " (status " + fileStatus + ")", cause);
        this.operation = operation;
        this.fileStatus = fileStatus;
    }

    /**
     * Returns the name of the failing operation, or {@code null} when not set.
     *
     * @return the failing operation, or {@code null}
     */
    public String getOperation() {
        return operation;
    }

    /**
     * Returns the originating status code, or {@code null} when not set.
     *
     * @return the status code, or {@code null}
     */
    public String getFileStatus() {
        return fileStatus;
    }
}
