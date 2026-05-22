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
 * Thrown when a write failed because the file has no remaining space for new records.
 *
 * <p>Corresponds to VSAM file-status code {@code '34'}.
 *
 * <p>Status {@code '34'} indicates the file is full and cannot accept additional records. The Java migration treats this as a fatal capacity condition distinct from {@link BoundaryViolationException} (status {@code '24'}).
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class FileFullException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code FileFullException} with the given diagnostic message and status
     * code {@code "34"}.
     *
     * @param message a human-readable diagnostic message
     */
    public FileFullException(String message) {
        super("34", message);
    }
}
