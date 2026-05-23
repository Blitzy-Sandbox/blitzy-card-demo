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
 * Thrown when an operation is invalid for the current state or attributes of the file.
 *
 * <p>Corresponds to VSAM file-status code {@code '41'}.
 *
 * <p>Status codes {@code '41'}, {@code '42'}, {@code '43'}, and {@code '46'} all map to this exception. They cover various invalid-operation conditions (operation attempted on a closed file, REWRITE without prior READ, sequential READ after a non-sequential operation, etc.). The Java migration treats all four as fatal because they indicate a violation of the file-state lifecycle.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class InvalidOperationException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code InvalidOperationException} with the given diagnostic message and status
     * code {@code "41"}.
     *
     * @param message a human-readable diagnostic message
     */
    public InvalidOperationException(String message) {
        super("41", message);
    }
}
