/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.status;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Sealed hierarchy modelling the {@code CDEMO-PGM-CONTEXT} 88-level conditions
 * from {@code app/cpy/COCOM01Y.cpy}:
 *
 * <pre>{@code
 * 10 CDEMO-PGM-CONTEXT      PIC 9(01).
 *    88 CDEMO-PGM-ENTER     VALUE 0.
 *    88 CDEMO-PGM-REENTER   VALUE 1.
 * }</pre>
 *
 * <p>Per AAP &sect;0.6.10 every 88-level value-space taxonomy is rendered as a
 * sealed hierarchy. Sites that switch on the value MUST enumerate {@link Enter}
 * and {@link Reenter} exhaustively (no default branch).
 */
@CobolProgram(
        value = "CDEMO-PGM-CONTEXT",
        sourcePath = "app/cpy/COCOM01Y.cpy",
        notes = "Sealed hierarchy modelling 88-level CDEMO-PGM-ENTER (0) / "
                + "CDEMO-PGM-REENTER (1); AAP §0.6.10"
)
public sealed interface PgmContext permits PgmContext.Enter, PgmContext.Reenter {

    /** Singleton: first entry to a program (no prior commarea state). */
    Enter ENTER = new Enter();

    /** Singleton: re-entry after a SEND-RECEIVE cycle (commarea state preserved). */
    Reenter REENTER = new Reenter();

    /** Returns the COBOL numeric code for this context. */
    int code();

    /**
     * Discriminator factory: selects the appropriate permit based on the COBOL
     * numeric code.
     *
     * @param code numeric code per {@code CDEMO-PGM-CONTEXT PIC 9(01)}
     * @return {@link #ENTER} for {@code 0}, {@link #REENTER} for {@code 1}
     * @throws IllegalArgumentException for any other value
     */
    static PgmContext fromCode(int code) {
        return switch (code) {
            case 0 -> ENTER;
            case 1 -> REENTER;
            default -> throw new IllegalArgumentException(
                    "Unknown CDEMO-PGM-CONTEXT code: " + code + " (expected 0 or 1)");
        };
    }

    /** First entry to a program — COBOL {@code 88 CDEMO-PGM-ENTER VALUE 0}. */
    record Enter() implements PgmContext {
        @Override
        public int code() {
            return 0;
        }
    }

    /** Re-entry after SEND-RECEIVE — COBOL {@code 88 CDEMO-PGM-REENTER VALUE 1}. */
    record Reenter() implements PgmContext {
        @Override
        public int code() {
            return 1;
        }
    }
}
