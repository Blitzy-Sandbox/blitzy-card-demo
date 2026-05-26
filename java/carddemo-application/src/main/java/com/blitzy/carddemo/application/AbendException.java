/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Java equivalent of a COBOL {@code CALL 'CEE3ABD'} ABEND in the batch
 * programs. Carries the COBOL ABCODE value (typically 999 in the CardDemo
 * programs) so the calling main can map it to a non-zero process exit
 * code. Used by every {@code 9999-ABEND-PROGRAM} translation across the
 * application layer.
 *
 * <p>This is a runtime exception (unchecked) so paragraph translations
 * can rethrow naturally without polluting their method signatures with
 * checked-exception annotations.
 *
 * <p>This exception sits at the {@code com.blitzy.carddemo.application}
 * package root because every translated program (batch and online) needs
 * a single, shared abend signal that the composition root in
 * {@code carddemo-app} can pattern-match on to set the appropriate
 * non-zero exit code (mirroring the COBOL job-step failure code).
 */
@CobolProgram(
        value = "CEE3ABD",
        sourcePath = "synthetic (IBM Language Environment service)",
        notes = "Generic abend signal raised by 9999-ABEND-PROGRAM translations across all batch programs"
)
public final class AbendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int abendCode;

    /**
     * Creates an AbendException with the given COBOL ABCODE and message.
     *
     * @param abendCode the COBOL ABCODE (e.g., 999)
     * @param message   a human-readable description
     */
    public AbendException(int abendCode, String message) {
        super(message);
        this.abendCode = abendCode;
    }

    /**
     * Creates an AbendException with the given COBOL ABCODE, message, and
     * cause.
     *
     * @param abendCode the COBOL ABCODE (e.g., 999)
     * @param message   a human-readable description
     * @param cause     the underlying cause, may be {@code null}
     */
    public AbendException(int abendCode, String message, Throwable cause) {
        super(message, cause);
        this.abendCode = abendCode;
    }

    /**
     * Returns the COBOL ABCODE associated with this abend.
     *
     * @return the abend code (typically 999 for application failures)
     */
    public int abendCode() {
        return abendCode;
    }
}
