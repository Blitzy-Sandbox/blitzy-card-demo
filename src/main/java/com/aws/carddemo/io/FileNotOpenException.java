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
 * Thrown when an OPEN operation failed because the named file does not exist (or is inaccessible).
 *
 * <p>Corresponds to VSAM file-status code {@code '35'}.
 *
 * <p>Status {@code '35'} is the most common deployment-environment failure: the file referenced by the {@code DDNAME} (in COBOL) or by the Spring Batch {@code FlatFileItemReader} resource (in Java) was not located. The Java migration treats this as a fatal ABEND because no processing can proceed without the input.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class FileNotOpenException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code FileNotOpenException} with the given diagnostic message and status
     * code {@code "35"}.
     *
     * @param message a human-readable diagnostic message
     */
    public FileNotOpenException(String message) {
        super("35", message);
    }
}
