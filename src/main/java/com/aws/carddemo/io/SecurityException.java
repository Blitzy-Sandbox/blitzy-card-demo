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
 * Thrown when the file's security profile denied the requested operation.
 *
 * <p>Corresponds to VSAM file-status code {@code '91'}.
 *
 * <p>Status {@code '91'} indicates an RACF/ACF2/Top-Secret access-control denial. The Java migration uses the CardDemo-namespaced {@code com.aws.carddemo.io.SecurityException} (deliberately distinct from {@link java.lang.SecurityException}) to make the I/O origin of the failure unambiguous in stack traces.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class SecurityException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code SecurityException} with the given diagnostic message and status
     * code {@code "91"}.
     *
     * @param message a human-readable diagnostic message
     */
    public SecurityException(String message) {
        super("91", message);
    }
}
