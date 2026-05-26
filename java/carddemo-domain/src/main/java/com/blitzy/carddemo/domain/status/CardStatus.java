/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.status;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Sealed hierarchy modelling the {@code CARD-ACTIVE-STATUS PIC X(01)} field
 * from {@code app/cpy/CVACT02Y.cpy}.
 *
 * <p>The data in {@code app/data/ASCII/carddata.txt} uses {@code 'Y'} for
 * active cards and {@code 'N'} for inactive cards. Per AAP &sect;0.6.10 this
 * closed binary taxonomy is rendered as a sealed hierarchy.
 */
@CobolProgram(
        value = "CARD-ACTIVE-STATUS",
        sourcePath = "app/cpy/CVACT02Y.cpy",
        notes = "Sealed binary status hierarchy: Active ('Y') / Inactive ('N'); AAP §0.6.10"
)
public sealed interface CardStatus permits CardStatus.Active, CardStatus.Inactive {

    /** Singleton for the active state. */
    Active ACTIVE = new Active();

    /** Singleton for the inactive state. */
    Inactive INACTIVE = new Inactive();

    /** Returns the single-character status code. */
    char code();

    /**
     * Discriminator factory: selects the appropriate permit based on the
     * single-character status code.
     *
     * @param code single character per {@code CARD-ACTIVE-STATUS PIC X(01)}
     * @return {@link #ACTIVE} for {@code 'Y'}, {@link #INACTIVE} for {@code 'N'}
     * @throws IllegalArgumentException for any other character
     */
    static CardStatus fromCode(char code) {
        return switch (code) {
            case 'Y' -> ACTIVE;
            case 'N' -> INACTIVE;
            default -> throw new IllegalArgumentException(
                    "Unknown CARD-ACTIVE-STATUS code: '" + code
                            + "' (expected 'Y' or 'N')");
        };
    }

    /** Card is active and may transact. */
    record Active() implements CardStatus {
        @Override
        public char code() {
            return 'Y';
        }
    }

    /** Card is inactive and is rejected by posting. */
    record Inactive() implements CardStatus {
        @Override
        public char code() {
            return 'N';
        }
    }
}
