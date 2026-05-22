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
 * Thrown when an OPEN operation failed because the file's attributes are incompatible with the OPEN mode.
 *
 * <p>Corresponds to VSAM file-status code {@code '37'}.
 *
 * <p>Status {@code '37'} indicates an OPEN-mode mismatch (e.g., OPEN OUTPUT on a read-only file). The Java migration treats this as a fatal configuration error.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class IncompatibleFileException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code IncompatibleFileException} with the given diagnostic message and status
     * code {@code "37"}.
     *
     * @param message a human-readable diagnostic message
     */
    public IncompatibleFileException(String message) {
        super("37", message);
    }
}
