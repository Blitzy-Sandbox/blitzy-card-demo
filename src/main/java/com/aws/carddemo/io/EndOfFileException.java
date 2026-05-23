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
 * Thrown when the end-of-file marker was reached on a sequential read.
 *
 * <p>Corresponds to VSAM file-status code {@code '10'}.
 *
 * <p>Returned by every COBOL program in the CardDemo source repository that performs sequential VSAM reads ({@code CBACT01C} lines 92-115 is the canonical example). The Java migration captures this in {@link FileStatusAction#END_OF_FILE} so Spring Batch readers can return {@code null} to terminate step iteration.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class EndOfFileException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code EndOfFileException} with the given diagnostic message and status
     * code {@code "10"}.
     *
     * @param message a human-readable diagnostic message
     */
    public EndOfFileException(String message) {
        super("10", message);
    }
}
