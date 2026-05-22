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
 * Thrown when a write violated the file's space boundary (e.g., exceeded the allocated extent).
 *
 * <p>Corresponds to VSAM file-status code {@code '24'}.
 *
 * <p>VSAM returns status {@code '24'} when a WRITE would exceed the file's primary or secondary allocation. The Java migration treats this as a fatal capacity-exhaustion condition.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class BoundaryViolationException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code BoundaryViolationException} with the given diagnostic message and status
     * code {@code "24"}.
     *
     * @param message a human-readable diagnostic message
     */
    public BoundaryViolationException(String message) {
        super("24", message);
    }
}
