/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 */
package com.blitzy.carddemo.domain.status;

/**
 * Unchecked exception that carries a typed {@link FileStatus.IoError} permit
 * as its first-class payload, used by translated COBOL programs at every site
 * that originally invoked {@code CALL 'CEE3ABD'} or otherwise terminated on a
 * non-recoverable FILE STATUS condition.
 *
 * <p>Introduced per M17 (code review checkpoint 5 remediation) to integrate
 * the sealed {@link FileStatus} hierarchy into use-case boundaries. Before
 * this class existed, translated programs threw plain
 * {@link IllegalStateException} and embedded the FILE STATUS code only in
 * the string message; downstream consumers therefore could not pattern-match
 * on the typed status. Throwing {@code FileStatusException} preserves the
 * COBOL "abend with FILE STATUS" observable outcome and adds compile-time
 * dispatch on the sealed hierarchy.
 *
 * <p>AAP &sect;0.6.10 requires that closed COBOL response-code sets translate
 * to sealed hierarchies in Java; this exception type is the bridge between
 * the JVM unchecked-exception machinery and that hierarchy. Catch sites
 * should pattern-match on {@link #ioError()} (or instance-check) rather than
 * parsing the string message.
 *
 * <p>Per the AAP minimal-change clause, this type is intentionally small: it
 * holds exactly one payload, a {@link FileStatus.IoError}, and an optional
 * causal {@link Throwable}. There are no setters, no Lombok, no Spring, no
 * Jakarta annotations.
 *
 * @since 1.0.0
 */
public final class FileStatusException extends RuntimeException {

    /**
     * Serial UID for {@link java.io.Serializable} compliance. The exception
     * is not expected to cross a serialization boundary in this offline
     * batch context, but {@link RuntimeException} is {@code Serializable}
     * so we honour the convention.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The typed FILE STATUS payload, never {@code null}. Catch sites should
     * pattern-match on this field (e.g.
     * {@code switch (ex.ioError()) { case IoError(int code, String desc) -> ... }})
     * rather than parsing the exception's string message.
     */
    private final FileStatus.IoError ioError;

    /**
     * Constructs a new {@link FileStatusException} carrying the supplied
     * {@link FileStatus.IoError} permit. The exception's
     * {@link #getMessage()} is derived from the permit's
     * {@code description} for readable diagnostic logging.
     *
     * @param ioError the typed FILE STATUS payload; must not be {@code null}
     * @throws NullPointerException if {@code ioError} is {@code null}
     */
    public FileStatusException(FileStatus.IoError ioError) {
        super(formatMessage(ioError));
        if (ioError == null) {
            throw new NullPointerException("ioError must not be null");
        }
        this.ioError = ioError;
    }

    /**
     * Constructs a new {@link FileStatusException} with an explicit message
     * suffix and an underlying cause. Used when wrapping an adapter-side
     * exception (e.g. {@link java.io.IOException}) at the use-case boundary.
     *
     * @param ioError       the typed FILE STATUS payload; must not be
     *                      {@code null}
     * @param messageSuffix additional context appended after the FILE
     *                      STATUS-derived message; {@code null} means no
     *                      suffix
     * @param cause         the underlying exception that triggered this
     *                      abend, or {@code null} when there is no
     *                      underlying cause
     * @throws NullPointerException if {@code ioError} is {@code null}
     */
    public FileStatusException(FileStatus.IoError ioError, String messageSuffix, Throwable cause) {
        super(formatMessage(ioError) + (messageSuffix == null ? "" : ": " + messageSuffix), cause);
        if (ioError == null) {
            throw new NullPointerException("ioError must not be null");
        }
        this.ioError = ioError;
    }

    /**
     * Returns the typed FILE STATUS payload. Catch sites should call this
     * method to obtain a value suitable for an exhaustive
     * pattern-matching {@code switch} over the {@link FileStatus} sealed
     * hierarchy.
     *
     * @return the {@link FileStatus.IoError} permit; never {@code null}
     */
    public FileStatus.IoError ioError() {
        return ioError;
    }

    /**
     * Returns the COBOL FILE STATUS code (e.g. {@code "30"}) that this
     * exception represents. Convenience accessor equivalent to
     * {@code ioError().code()} but returns the 2-character string form
     * used in COBOL {@code DISPLAY 'FILE STATUS IS: NNNN'} output.
     *
     * @return the 2-character FILE STATUS code; never {@code null}
     */
    public String cobolCode() {
        return ioError.toCobolCode();
    }

    /**
     * Formats the exception message from the IoError payload. Defensive
     * against a {@code null} payload to avoid a NullPointerException
     * during exception construction; the canonical constructor below
     * separately rejects null payloads after the super() call returns,
     * preserving the historical "exception construction always succeeds"
     * invariant.
     *
     * @param ioError the typed payload (possibly {@code null} mid-construction)
     * @return a human-readable message string; never {@code null}
     */
    private static String formatMessage(FileStatus.IoError ioError) {
        if (ioError == null) {
            return "FILE STATUS error (no payload)";
        }
        return "FILE STATUS " + ioError.toCobolCode()
                + " (" + ioError.code() + "): " + ioError.description();
    }
}
