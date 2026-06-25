/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signals a service-layer programmatic validation failure affecting one or more
 * input fields, including cross-field checks that declarative bean-validation
 * annotations cannot express.
 *
 * <p>Instances optionally carry a per-field error map (field name to a
 * human-readable validation message) so that callers can surface a structured,
 * per-field error body. The map returned by {@link #getFieldErrors()} is never
 * {@code null}, is unmodifiable, and preserves the insertion order of the
 * entries supplied at construction time, keeping field-error ordering
 * deterministic.
 *
 * <p>The exception extends {@link java.lang.RuntimeException} directly and
 * depends only on {@code java.util} collections, so it carries no coupling to
 * any persistence, web, validation, or application package. HTTP status
 * selection is intentionally not performed here; the centralized exception
 * handler is responsible for mapping this type to its transport-level response.
 */
public class ValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Immutable, insertion-ordered map of field name to validation message.
     * Never {@code null}; an empty map indicates that no per-field detail was
     * supplied. The runtime values held here ({@link Collections#emptyMap()} or
     * {@link Collections#unmodifiableMap(Map)} wrapping a {@link LinkedHashMap})
     * are serializable, so the exception instance remains serializable.
     */
    @SuppressWarnings("serial")
    private final Map<String, String> fieldErrors;

    /**
     * Creates an exception with the supplied detail message and no per-field
     * error detail.
     *
     * @param message the detail message
     */
    public ValidationException(String message) {
        super(message);
        this.fieldErrors = Collections.emptyMap();
    }

    /**
     * Creates an exception with the supplied detail message and per-field error
     * detail. A defensive, unmodifiable, insertion-ordered copy of the supplied
     * map is stored; a {@code null} or empty argument yields an empty map.
     *
     * @param message     the detail message
     * @param fieldErrors map of field name to validation message; may be
     *                    {@code null} or empty
     */
    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = (fieldErrors == null || fieldErrors.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    /**
     * Creates an exception with the supplied detail message and underlying
     * cause and no per-field error detail.
     *
     * @param message the detail message
     * @param cause   the underlying cause, which may be {@code null}
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldErrors = Collections.emptyMap();
    }

    /**
     * Creates an exception with the supplied detail message, per-field error
     * detail, and underlying cause. A defensive, unmodifiable, insertion-ordered
     * copy of the supplied map is stored; a {@code null} or empty argument
     * yields an empty map.
     *
     * @param message     the detail message
     * @param fieldErrors map of field name to validation message; may be
     *                    {@code null} or empty
     * @param cause       the underlying cause, which may be {@code null}
     */
    public ValidationException(String message, Map<String, String> fieldErrors, Throwable cause) {
        super(message, cause);
        this.fieldErrors = (fieldErrors == null || fieldErrors.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    /**
     * Returns the per-field validation errors carried by this exception.
     *
     * <p>The returned map is never {@code null}, is unmodifiable (mutation
     * attempts raise {@link UnsupportedOperationException}), and preserves the
     * insertion order of the entries supplied at construction time.
     *
     * @return an unmodifiable, insertion-ordered map of field name to
     *         validation message; empty when no per-field detail was supplied
     */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
