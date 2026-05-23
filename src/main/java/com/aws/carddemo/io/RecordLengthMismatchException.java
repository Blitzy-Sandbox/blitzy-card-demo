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
package com.aws.carddemo.io;

/**
 * Thrown when the length of the record read or written does not match the file's declared record length.
 *
 * <p>Corresponds to VSAM file-status code {@code '04'}.
 *
 * <p>The COBOL runtime returns status {@code '04'} when a sequential READ retrieves a record whose length differs from the {@code FD} declaration. The Java migration treats this as recoverable (LOG_AND_CONTINUE) because subsequent records may still be valid.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class RecordLengthMismatchException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code RecordLengthMismatchException} with the given diagnostic message and status
     * code {@code "04"}.
     *
     * @param message a human-readable diagnostic message
     */
    public RecordLengthMismatchException(String message) {
        super("04", message);
    }
}
