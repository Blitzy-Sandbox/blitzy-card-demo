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
 * Thrown when the operation could not proceed because a required system resource is unavailable.
 *
 * <p>Corresponds to VSAM file-status code {@code '93'}.
 *
 * <p>Status {@code '93'} indicates a system-level resource shortage (no free buffers, no available data spaces, etc.). The Java migration treats this as a fatal environmental failure.
 *
 * @see FileStatusMapper
 * @see VsamException
 */
public class ResourceUnavailableException extends VsamException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code ResourceUnavailableException} with the given diagnostic message and status
     * code {@code "93"}.
     *
     * @param message a human-readable diagnostic message
     */
    public ResourceUnavailableException(String message) {
        super("93", message);
    }
}
