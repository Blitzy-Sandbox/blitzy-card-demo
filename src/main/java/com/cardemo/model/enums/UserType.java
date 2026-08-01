/*
 * ******************************************************************
 * Program     : UserType.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 enumeration
 * Function    : Typed replacement for the CDEMO-USER-TYPE 88-level
 *               condition names. Carries the one character code that
 *               the COMMAREA field PIC X(01) holds, and nothing more:
 *               translating that code into a Spring Security role is
 *               deliberately left to the security and configuration
 *               packages, so this type stays a pure data holder.
 * Source      : app/cpy/COCOM01Y.cpy:L26-L28 @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44 @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.enums;

import java.util.Optional;

/**
 * The two user classes of the CardDemo application, replacing the two COBOL 88-level condition names declared
 * on {@code CDEMO-USER-TYPE} in {@code app/cpy/COCOM01Y.cpy}.
 *
 * <p>This is a pure value type. It holds one character, performs no I/O, reads no configuration, logs
 * nothing, depends on no other CardDemo type and depends on no framework: the whole of its public
 * surface is the code accessor and the lookups that invert it.
 *
 * <h2>Source field contract</h2>
 *
 * <p>{@code app/cpy/COCOM01Y.cpy} declares, verbatim, nested inside {@code CDEMO-GENERAL-INFO} of
 * {@code 01 CARDDEMO-COMMAREA}:
 *
 * <pre>
 *              10 CDEMO-USER-TYPE               PIC X(01).        &lt;- L26
 *                 88 CDEMO-USRTYP-ADMIN         VALUE 'A'.        &lt;- L27
 *                 88 CDEMO-USRTYP-USER          VALUE 'U'.        &lt;- L28
 * </pre>
 *
 * <p><strong>Exactly two condition names exist on that field, so this enum has exactly two constants.</strong>
 * The field is {@code PIC X(01)}, a single byte, which is why each constant carries a {@code char} rather
 * than a string, an ordinal or an integer.
 *
 * <h2>Independent corroboration of completeness</h2>
 *
 * <p>The ten seeded user records supplied in stream to IEBGENER at {@code app/jcl/DUSRSECJ.jcl:L35-L44}
 * agree. Under the {@code app/cpy/CSUSR01Y.cpy} layout - {@code SEC-USR-ID PIC X(08)},
 * {@code SEC-USR-FNAME PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)}, {@code SEC-USR-PWD PIC X(08)},
 * {@code SEC-USR-TYPE PIC X(01)}, {@code SEC-USR-FILLER PIC X(23)}, totalling the 80 bytes that
 * {@code DUSRSECJ.jcl:L48} declares as {@code LRECL=80} - the type byte sits at record position 57. Across
 * all ten records that byte takes exactly two distinct values: five {@code 'A'} and five {@code 'U'}. No
 * third value occurs anywhere in the seed set, so the two constants below are not merely correct but
 * complete.
 *
 * <h2>Case sensitivity is deliberate, and is parity behaviour</h2>
 *
 * <p>The lookups below are <strong>case sensitive</strong>: {@code 'a'} is not {@link #ADMIN} and
 * {@code 'u'} is not {@link #USER}. This reproduces the source rather than tightening it.
 * {@code app/cbl/COSGN00C.cbl:L132} and {@code :L135} apply {@code FUNCTION UPPER-CASE} to the entered
 * user id and password only; {@code :L227} then moves {@code SEC-USR-TYPE} into
 * {@code CDEMO-USER-TYPE} unfolded, and {@code :L230} tests {@code CDEMO-USRTYP-ADMIN}, which compares
 * the byte against {@code 'A'} exactly. A lower case type byte therefore satisfies neither condition name
 * in the legacy program, and it must satisfy neither lookup here. Because no case folding is performed,
 * this class contains no locale sensitive operation of any kind and its behaviour cannot vary with the
 * platform default locale, charset or time zone.
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <ul>
 *   <li>It does not map either constant onto a granted authority name, and exposes no authority naming
 *       convention of any kind. Role mapping is an authorisation concern owned by
 *       {@code com.cardemo.config.SecurityConfig} and {@code com.cardemo.security.JwtTokenProvider},
 *       which consume {@link #getCode()}. Keeping that mapping out of this class is what keeps the data
 *       holder free of any Spring dependency.</li>
 *   <li>It carries no persistence annotation. The column mapping for the stored type character belongs to
 *       {@code com.cardemo.model.entity.UserSecurity}.</li>
 *   <li>It carries no credential material. The seeded records cited above contain a plain text password
 *       field, which is to be hashed by {@code V3__seed_data.sql} (planned; absent at this commit) and is never
 * represented here.</li>
 * </ul>
 *
 * <p>Instances are immutable, stateless and inherently thread safe.
 *
 * @see #fromCode(char)
 * @see #requireFromCode(char)
 */
public enum UserType {

    /**
     * An administrative user, corresponding to the condition name {@code CDEMO-USRTYP-ADMIN} with
     * {@code VALUE 'A'} at {@code app/cpy/COCOM01Y.cpy:L27}.
     */
    ADMIN('A'),

    /**
     * A standard, non administrative user, corresponding to the condition name
     * {@code CDEMO-USRTYP-USER} with {@code VALUE 'U'} at {@code app/cpy/COCOM01Y.cpy:L28}.
     *
     * <p>Corroborated by the <strong>remaining five of the ten</strong> seeded records at
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44} - the last five rows of the inline stream - each of which
     * holds {@code 'U'} in the {@code SEC-USR-TYPE} byte. Their identifiers are likewise not reproduced;
     * the synthetic stand-ins used here are {@code STDUSR01} through {@code STDUSR05}.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:L136-L137} shows the legacy consumer of this distinction: a menu
     * option flagged for administrators is withheld when {@code CDEMO-USRTYP-USER} holds.
     */
    USER('U');

    /**
     * The single character that {@code CDEMO-USER-TYPE PIC X(01)} holds for this user class, transcribed from
     * the {@code VALUE} clause of the corresponding condition name.
     */
    private final char code;

    /**
     * Binds a constant to its one character external code.
     *
     * @param code the character transcribed from the {@code VALUE} clause of this constant's condition name at
     * {@code app/cpy/COCOM01Y.cpy:L27-L28}
     */
    private UserType(final char code) {
        this.code = code;
    }

    /**
     * Returns the single character external code for this user class.
     *
     * @return {@code 'A'} for {@link #ADMIN} or {@code 'U'} for {@link #USER}
     */
    public char getCode() {
        return this.code;
    }

    /**
     * Resolves a single character code to its user class, reporting an unrecognised code as an empty result
     * rather than as a failure.
     *
     * @param code the candidate {@code CDEMO-USER-TYPE} character.
     * @return the matching constant, or {@link Optional#empty()} if the character is not one of the two codes
     * defined by the copybook
     */
    public static Optional<UserType> fromCode(final char code) {
        return switch (code) {
            case 'A' -> Optional.of(ADMIN);
            case 'U' -> Optional.of(USER);
            default -> Optional.empty();
        };
    }

    /**
     * Resolves a one character string code to its user class, reporting anything unrecognised as an empty
     * result rather than as a failure.
     *
     * @param code the candidate {@code CDEMO-USER-TYPE} value.
     * @return the matching constant, or {@link Optional#empty()} if the argument is {@code null}, is not
     * exactly one character long, or is not one of the two codes defined by the copybook
     */
    public static Optional<UserType> fromCode(final String code) {
        if (code == null) {
            return Optional.empty();
        }
        if (code.length() != 1) {
            return Optional.empty();
        }
        return fromCode(code.charAt(0));
    }

    /**
     * Resolves a single character code to its user class, failing loudly when the code is not one of the
     * two the copybook defines.
     *
     * <p>Use this overload only where a recognised code is a genuine invariant, such as re-reading a value
     * this application itself wrote. Where an unrecognised code is a foreseeable input, prefer
     * {@link #fromCode(char)} and handle the empty result. There is deliberately no permissive fallback:
     * an unknown code never silently resolves to {@link #USER}, to {@link #ADMIN}, to the first declared
     * constant or to {@code null}.
     *
     * <p><b>The thrown message identifies the offending character by its hexadecimal code point only, and
     * never echoes the raw character.</b> That is a deliberate log injection defence rather than a
     * stylistic preference. This overload is reachable from a data path - the {@code sec_usr_type} column
     * converter, which is fed by whatever a database row actually holds - so the rejected value is not
     * necessarily anything this application wrote. Interpolating it verbatim would let a carriage return
     * or a line feed in a corrupt row terminate the current line and forge a following one in any sink
     * that stores one event per line, and would let an escape sequence reach a terminal that renders it.
     * The hexadecimal rendering is strictly more diagnostic in any case: the legacy records are fixed
     * width and blank padded, so a blank, a low value byte, a non breaking space and a genuinely wrong
     * letter are indistinguishable when printed raw, whereas {@code 0x20}, {@code 0x00}, {@code 0xa0}
     * and {@code 0x5a} are not. Nothing diagnosable is lost by withholding the character, because a
     * single code point determines it completely.
     *
     * <p>This method is a pure function apart from the exception it may raise: it mutates no state and
     * performs no I/O.
     *
     * @param code the candidate {@code CDEMO-USER-TYPE} character
     * @return the matching constant, never {@code null}
     * @throws IllegalArgumentException if the character is not one of the two codes defined by
     *                                 {@code app/cpy/COCOM01Y.cpy:L27-L28}; the message identifies the
     *                                 rejected character by hexadecimal code point and never reproduces
     *                                 the character itself
     */
    public static UserType requireFromCode(final char code) {
        return fromCode(code).orElseThrow(() -> new IllegalArgumentException(
                "Unrecognised CDEMO-USER-TYPE code at code point 0x" + Integer.toHexString(code)
                        + " (the rejected character is withheld from this message so that a control "
                        + "character in corrupt data cannot forge a log entry); the only codes defined "
                        + "by app/cpy/COCOM01Y.cpy:L27-L28 are 'A' for ADMIN and 'U' for USER"));
    }
}
