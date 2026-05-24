/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.status;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Sealed hierarchy modelling the {@code ACCT-ACTIVE-STATUS PIC X(01)} field
 * from {@code app/cpy/CVACT01Y.cpy}.
 *
 * <p>The COBOL source does not declare explicit 88-level conditions for the
 * field, but the data in {@code app/data/ASCII/acctdata.txt} uses {@code 'Y'}
 * for active accounts and {@code 'N'} for inactive accounts. Per AAP
 * &sect;0.6.10 this closed binary taxonomy is rendered as a sealed hierarchy
 * to allow compile-time-exhaustive switch dispatch.
 */
@CobolProgram(
        value = "ACCT-ACTIVE-STATUS",
        sourcePath = "app/cpy/CVACT01Y.cpy",
        notes = "Sealed binary status hierarchy: Active ('Y') / Inactive ('N'); AAP §0.6.10"
)
public sealed interface AccountStatus
        permits AccountStatus.Active, AccountStatus.Inactive {

    /** Singleton for the active state ({@code 'Y'}). */
    Active ACTIVE = new Active();

    /** Singleton for the inactive state ({@code 'N'}). */
    Inactive INACTIVE = new Inactive();

    /** Returns the single-character status code. */
    char code();

    /**
     * Discriminator factory: selects the appropriate permit based on the
     * single-character status code.
     *
     * @param code single character per {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * @return {@link #ACTIVE} for {@code 'Y'}, {@link #INACTIVE} for {@code 'N'}
     * @throws IllegalArgumentException for any other character
     */
    static AccountStatus fromCode(char code) {
        return switch (code) {
            case 'Y' -> ACTIVE;
            case 'N' -> INACTIVE;
            default -> throw new IllegalArgumentException(
                    "Unknown ACCT-ACTIVE-STATUS code: '" + code
                            + "' (expected 'Y' or 'N')");
        };
    }

    /** Account is active and may transact ({@code 'Y'}). */
    record Active() implements AccountStatus {
        @Override
        public char code() {
            return 'Y';
        }
    }

    /** Account is inactive and is rejected by posting ({@code 'N'}). */
    record Inactive() implements AccountStatus {
        @Override
        public char code() {
            return 'N';
        }
    }
}
