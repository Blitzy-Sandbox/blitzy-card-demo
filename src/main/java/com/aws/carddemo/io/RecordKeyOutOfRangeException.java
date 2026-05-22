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
 * Thrown when the requested key falls outside the range of keys defined on the file.
 *
 * <p>Corresponds to VSAM file-status code {@code '14'}.
 *
 * <p>VSAM returns status {@code '14'} when a keyed read references a key value below the file's lowest key or above its highest key. This indicates a structural problem in the caller's key-range computation and is always fatal.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class RecordKeyOutOfRangeException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code RecordKeyOutOfRangeException} with the given diagnostic message and status
     * code {@code "14"}.
     *
     * @param message a human-readable diagnostic message
     */
    public RecordKeyOutOfRangeException(String message) {
        super("14", message);
    }
}
