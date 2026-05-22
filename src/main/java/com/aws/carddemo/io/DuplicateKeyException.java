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
 * Thrown when a write attempted to insert a key that already exists in a unique-key file.
 *
 * <p>Corresponds to VSAM file-status code {@code '22'}.
 *
 * <p>VSAM returns status {@code '22'} when a WRITE or REWRITE provides a primary key that already exists in a KSDS with unique-key constraint. The Java migration translates this to an ABEND to preserve COBOL semantics.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class DuplicateKeyException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code DuplicateKeyException} with the given diagnostic message and status
     * code {@code "22"}.
     *
     * @param message a human-readable diagnostic message
     */
    public DuplicateKeyException(String message) {
        super("22", message);
    }
}
