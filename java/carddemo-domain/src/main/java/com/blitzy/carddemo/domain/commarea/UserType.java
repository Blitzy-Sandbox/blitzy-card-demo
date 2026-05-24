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
package com.blitzy.carddemo.domain.commarea;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Sealed hierarchy translating the COBOL 88-level conditions on
 * {@code CDEMO-USER-TYPE} declared in {@code app/cpy/COCOM01Y.cpy}.
 *
 * <p>The COBOL definition (verbatim from the copybook, lines 26&ndash;28):
 * <pre>
 * 10 CDEMO-USER-TYPE               PIC X(01).
 *    88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
 *    88 CDEMO-USRTYP-USER          VALUE 'U'.
 * </pre>
 *
 * <p>This sealed interface partitions the closed value space {@code {'A', 'U'}}
 * exhaustively. Callers MUST use pattern-matching {@code switch} expressions
 * with all permits enumerated; the Java 25 compiler enforces exhaustiveness so
 * adding a new permit (which would only happen if the COBOL source adds a new
 * 88-level condition) is a compile-time error at every call site until handled.
 *
 * <p><strong>NO {@code default} branch is permitted</strong> in a switch over
 * {@code UserType} (per AAP &sect;0.6.2 and &sect;0.6.7): exhaustiveness
 * checking IS the safety guarantee. The single {@code default} branch in
 * {@link #fromIndicator(char)} is over a primitive {@code char}, not over this
 * sealed type, and exists to surface invalid input rather than to mask missing
 * cases.
 *
 * <h2>Encoding</h2>
 * The {@link #indicator()} method returns the single ASCII character that the
 * COBOL field holds in the underlying byte buffer ({@code 'A'} for
 * {@link Admin}, {@code 'U'} for {@link User}). Persistence of this value as a
 * single byte within the {@code CARDDEMO-COMMAREA} buffer is the responsibility
 * of the enclosing commarea encoder; this type is a pure value object and
 * performs no I/O of its own.
 *
 * <h2>Singleton Instances</h2>
 * Because both permits are zero-component records, all {@link Admin} instances
 * are {@code .equals()}-equal and all {@link User} instances are
 * {@code .equals()}-equal. Use the canonical {@link #ADMIN} and {@link #USER}
 * constants instead of allocating fresh instances; use
 * {@link #fromIndicator(char)} to map from a raw character to a canonical
 * instance.
 *
 * <h2>Pattern-Matching Switch Example</h2>
 * <pre>{@code
 * boolean canAccessAdminMenu = switch (userType) {
 *     case UserType.Admin a -> true;
 *     case UserType.User  u -> false;
 *     // NO default branch — the compiler enforces exhaustiveness
 * };
 * }</pre>
 *
 * <h2>Convenience Predicates</h2>
 * For call sites where pattern matching is overkill ({@code if/else} style
 * dispatch), {@link #isAdmin()} and {@link #isUser()} return the matching
 * boolean. These predicates do NOT replace exhaustive pattern matching; they
 * are convenience accessors on top of the sealed hierarchy.
 *
 * <h2>Case Sensitivity</h2>
 * The COBOL literal {@code VALUE 'A'} is case-sensitive for data; the Java
 * translation preserves uppercase strictness. {@link #fromIndicator(char)}
 * rejects lowercase {@code 'a'} and {@code 'u'} with
 * {@link IllegalArgumentException}, matching the closed-set semantics of the
 * 88-level conditions.
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "COCOM01Y",
        sourcePath = "app/cpy/COCOM01Y.cpy",
        translationDate = "2025-10-15",
        notes = "Sealed hierarchy for 88-level conditions on CDEMO-USER-TYPE: "
                + "CDEMO-USRTYP-ADMIN ('A') and CDEMO-USRTYP-USER ('U'). "
                + "Closed value space {'A', 'U'}; pattern-matching switch must be exhaustive."
)
public sealed interface UserType permits UserType.Admin, UserType.User {

    /**
     * Returns the single ASCII character indicator held in the COBOL
     * {@code CDEMO-USER-TYPE PIC X(01)} field. Returns {@code 'A'} for
     * {@link Admin}, {@code 'U'} for {@link User}.
     *
     * @return {@code 'A'} for Admin, {@code 'U'} for User
     */
    char indicator();

    /**
     * @return {@code true} if this is an {@link Admin} (administrative) user
     *         type; {@code false} otherwise
     */
    default boolean isAdmin() {
        return this instanceof Admin;
    }

    /**
     * @return {@code true} if this is a {@link User} (standard end-user) user
     *         type; {@code false} otherwise
     */
    default boolean isUser() {
        return this instanceof User;
    }

    /**
     * Administrative user: {@code CDEMO-USRTYP-ADMIN VALUE 'A'}. Admin users
     * have access to administrative menus and operations (translated from the
     * COBOL admin-menu program {@code COADM01C}).
     *
     * <p>This permit is a zero-component record; all {@code Admin} instances
     * are {@code .equals()}-equal. Prefer the canonical {@link #ADMIN} constant
     * over fresh allocations.
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "88-level CDEMO-USRTYP-ADMIN VALUE 'A'"
    )
    record Admin() implements UserType {
        @Override
        public char indicator() {
            return 'A';
        }
    }

    /**
     * Standard end-user: {@code CDEMO-USRTYP-USER VALUE 'U'}. Standard users
     * have access to main menu operations (translated from the COBOL main-menu
     * program {@code COMEN01C}) but not administrative menus.
     *
     * <p>This permit is a zero-component record; all {@code User} instances
     * are {@code .equals()}-equal. Prefer the canonical {@link #USER} constant
     * over fresh allocations.
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "88-level CDEMO-USRTYP-USER VALUE 'U'"
    )
    record User() implements UserType {
        @Override
        public char indicator() {
            return 'U';
        }
    }

    /**
     * Canonical singleton instance for {@link Admin} (indicator {@code 'A'}).
     * Prefer this constant to {@code new Admin()} to avoid unnecessary
     * allocation.
     */
    UserType ADMIN = new Admin();

    /**
     * Canonical singleton instance for {@link User} (indicator {@code 'U'}).
     * Prefer this constant to {@code new User()} to avoid unnecessary
     * allocation.
     */
    UserType USER = new User();

    /**
     * Returns the canonical {@code UserType} instance for the given indicator
     * character. This is the discriminator-based factory that maps a single
     * byte/character from a fixed-width COBOL record buffer to the
     * corresponding sealed-type permit.
     *
     * <p>The {@code default} branch below is over the primitive {@code char}
     * type, not over the sealed {@code UserType}, and exists to surface
     * invalid input rather than to mask missing cases. Per AAP &sect;0.6.2 this
     * is the correct idiom for parsing untrusted input: switches over the
     * sealed type itself remain exhaustive with no {@code default}.
     *
     * @param indicator {@code 'A'} for {@link Admin}, {@code 'U'} for
     *                  {@link User}
     * @return {@link #ADMIN} if {@code indicator == 'A'}; {@link #USER} if
     *         {@code indicator == 'U'}
     * @throws IllegalArgumentException if {@code indicator} is neither
     *                                  {@code 'A'} nor {@code 'U'}; the message
     *                                  includes the offending character and
     *                                  its hexadecimal code point for
     *                                  diagnostic purposes
     */
    static UserType fromIndicator(char indicator) {
        return switch (indicator) {
            case 'A' -> ADMIN;
            case 'U' -> USER;
            default  -> throw new IllegalArgumentException(
                    "Invalid UserType indicator: '" + indicator
                            + "' (U+" + Integer.toHexString(indicator) + ")"
                            + " (expected 'A' for Admin or 'U' for User)");
        };
    }
}
