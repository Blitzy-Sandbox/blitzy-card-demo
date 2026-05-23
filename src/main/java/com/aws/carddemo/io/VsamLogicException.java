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
 * Thrown when a logic error: the requested operation conflicts with the current file state.
 *
 * <p>Corresponds to VSAM file-status code {@code '92'}.
 *
 * <p>Status {@code '92'} indicates an attempt to perform an operation that is structurally invalid given the file's current state (e.g., DELETE without prior READ on a KSDS, REWRITE without a current record position). The Java migration treats this as a fatal programming error.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class VsamLogicException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code VsamLogicException} with the given diagnostic message and status
     * code {@code "92"}.
     *
     * @param message a human-readable diagnostic message
     */
    public VsamLogicException(String message) {
        super("92", message);
    }
}
