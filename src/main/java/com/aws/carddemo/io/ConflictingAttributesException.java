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
 * Thrown when an operation failed because the file's runtime attributes conflict with its catalog definition.
 *
 * <p>Corresponds to VSAM file-status code {@code '39'}.
 *
 * <p>Status {@code '39'} indicates a catalog-vs-runtime mismatch (e.g., the file was redefined under a different RECORD-LENGTH since the program was bound). The Java migration treats this as a fatal definition error.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class ConflictingAttributesException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code ConflictingAttributesException} with the given diagnostic message and status
     * code {@code "39"}.
     *
     * @param message a human-readable diagnostic message
     */
    public ConflictingAttributesException(String message) {
        super("39", message);
    }
}
