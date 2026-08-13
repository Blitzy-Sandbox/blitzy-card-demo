package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * Raised when a dataset operation has produced, or would produce, a state the COBOL original cannot produce
 * - so the state must not be allowed to stand.
 */
public final class DatasetIntegrityException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final String operation;

    public DatasetIntegrityException(String operation, String message) {
        super(Objects.requireNonNull(message, "A message is required so a refused commit can be "
                + "diagnosed without reading the record it refused to write"));
        this.operation = Objects.requireNonNull(operation, "An operation name is required so a refused "
                + "commit can be attributed");
    }

    public String operation() {
        return operation;
    }
}
