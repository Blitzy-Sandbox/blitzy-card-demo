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

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of translating a VSAM file-status code through
 * {@link FileStatusMapper#map(String)}.
 *
 * <p>Carries two pieces of information:
 * <ul>
 *   <li>The {@link FileStatusAction action} the caller must take
 *       ({@link FileStatusAction#CONTINUE CONTINUE}, {@link FileStatusAction#END_OF_FILE END_OF_FILE},
 *       {@link FileStatusAction#LOG_AND_CONTINUE LOG_AND_CONTINUE}, or
 *       {@link FileStatusAction#ABEND ABEND}).</li>
 *   <li>An optional {@link Throwable exception} that, when present, can be
 *       rethrown by the caller to surface the underlying VSAM failure as a
 *       typed Java exception. Success codes ({@code '00'}, {@code '02'},
 *       {@code '05'}, {@code '97'}) carry no exception.</li>
 * </ul>
 *
 * <p>This class is final and immutable: instances are safe to share across
 * threads and across concurrent test invocations.
 *
 * @see FileStatusMapper
 * @see FileStatusAction
 */
public final class FileStatusResult {

    private final FileStatusAction action;
    private final Throwable exception;

    /**
     * Creates a result with no associated exception. Typically used for the
     * {@link FileStatusAction#CONTINUE CONTINUE} action that corresponds to a
     * successful I/O operation.
     *
     * @param action the action enum value (must not be {@code null})
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public FileStatusResult(FileStatusAction action) {
        this(action, null);
    }

    /**
     * Creates a result with the given action and (possibly {@code null})
     * exception.
     *
     * @param action    the action enum value (must not be {@code null})
     * @param exception the exception to surface to the caller, or
     *                  {@code null} if no exception applies
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public FileStatusResult(FileStatusAction action, Throwable exception) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.exception = exception;
    }

    /**
     * Returns the action the caller must take in response to the original VSAM
     * status code.
     *
     * @return the non-{@code null} {@link FileStatusAction}
     */
    public FileStatusAction getAction() {
        return action;
    }

    /**
     * Returns the optional exception carried by this result. Present for
     * non-success status codes; empty for success codes (status {@code '00'},
     * {@code '02'}, {@code '05'}, {@code '97'}).
     *
     * @return an {@link Optional} containing the exception, or
     *         {@link Optional#empty()} if no exception applies
     */
    public Optional<Throwable> getException() {
        return Optional.ofNullable(exception);
    }
}
