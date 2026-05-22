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
 * Thrown when a permanent I/O error occurred that could not be classified more specifically.
 *
 * <p>Corresponds to VSAM file-status code {@code '30'}.
 *
 * <p>Status {@code '30'} is VSAM's catch-all for hardware-level I/O failures (uncorrectable parity errors, dropped channels, etc.) that the runtime cannot recover from. The Java migration treats this as a fatal ABEND.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class IoException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code IoException} with the given diagnostic message and status
     * code {@code "30"}.
     *
     * @param message a human-readable diagnostic message
     */
    public IoException(String message) {
        super("30", message);
    }
}
