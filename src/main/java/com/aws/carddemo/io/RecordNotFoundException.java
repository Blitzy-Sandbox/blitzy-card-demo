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
 * Thrown when a keyed read could not locate any record with the requested key.
 *
 * <p>Corresponds to VSAM file-status code {@code '23'}.
 *
 * <p>Status {@code '23'} is the most semantically interesting code in the CardDemo migration. The COBOL program {@code CBACT04C} lines 422-440 treats {@code '23'} as recoverable: when a disclosure-group read returns {@code '23'}, the program retries with the literal {@code DEFAULT} group key. The Java migration translates this to {@link FileStatusAction#LOG_AND_CONTINUE} so the caller can catch and substitute the default.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class RecordNotFoundException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code RecordNotFoundException} with the given diagnostic message and status
     * code {@code "23"}.
     *
     * @param message a human-readable diagnostic message
     */
    public RecordNotFoundException(String message) {
        super("23", message);
    }
}
