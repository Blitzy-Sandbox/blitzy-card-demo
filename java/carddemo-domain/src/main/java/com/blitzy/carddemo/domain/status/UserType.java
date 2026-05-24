/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.status;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Sealed hierarchy modelling the {@code CDEMO-USER-TYPE} 88-level conditions
 * from {@code app/cpy/COCOM01Y.cpy}:
 *
 * <pre>{@code
 * 10 CDEMO-USER-TYPE        PIC X(01).
 *    88 CDEMO-USRTYP-ADMIN  VALUE 'A'.
 *    88 CDEMO-USRTYP-USER   VALUE 'U'.
 * }</pre>
 *
 * <p>Per AAP &sect;0.6.10 every 88-level value-space taxonomy is rendered as a
 * sealed hierarchy. The {@link #fromCode(char)} factory is the discriminator
 * that selects the appropriate permit; sites that switch on the value MUST
 * enumerate {@link Admin} and {@link User} exhaustively (no default branch).
 *
 * <h2>Routing usage (AAP &sect;0.6.10)</h2>
 * <p>{@code COSGN00C} dispatches the user to the admin menu ({@code COADM01C})
 * or the regular main menu ({@code COMEN01C}) based on this value. The Java
 * translation uses a pattern-matching {@code switch} (no default) so that any
 * future addition of a new user type forces every dispatch site to handle it.
 */
@CobolProgram(
        value = "CDEMO-USER-TYPE",
        sourcePath = "app/cpy/COCOM01Y.cpy",
        notes = "Sealed hierarchy modelling 88-level CDEMO-USRTYP-ADMIN ('A') / "
                + "CDEMO-USRTYP-USER ('U'); AAP §0.6.10"
)
public sealed interface UserType permits UserType.Admin, UserType.User {

    /** Singleton instance of the admin user type ({@code 'A'}). */
    Admin ADMIN = new Admin();

    /** Singleton instance of the regular user type ({@code 'U'}). */
    User USER = new User();

    /**
     * Returns the COBOL single-character code for this user type
     * ({@code 'A'} for {@link Admin}, {@code 'U'} for {@link User}).
     */
    char code();

    /**
     * Discriminator factory: selects the appropriate permit based on the COBOL
     * single-character code. Any other character throws
     * {@link IllegalArgumentException}, mirroring the closed-set semantics of
     * the COBOL 88-level conditions.
     *
     * @param code single character per {@code CDEMO-USER-TYPE PIC X(01)}
     * @return {@link #ADMIN} for {@code 'A'}, {@link #USER} for {@code 'U'}
     * @throws IllegalArgumentException for any other character
     */
    static UserType fromCode(char code) {
        return switch (code) {
            case 'A' -> ADMIN;
            case 'U' -> USER;
            default -> throw new IllegalArgumentException(
                    "Unknown CDEMO-USER-TYPE code: '" + code + "' (expected 'A' or 'U')");
        };
    }

    /** Admin user type — COBOL {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}. */
    record Admin() implements UserType {
        @Override
        public char code() {
            return 'A';
        }
    }

    /** Regular user type — COBOL {@code 88 CDEMO-USRTYP-USER VALUE 'U'}. */
    record User() implements UserType {
        @Override
        public char code() {
            return 'U';
        }
    }
}
