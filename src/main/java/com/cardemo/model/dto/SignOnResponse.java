/*
 * ******************************************************************
 * Program     : SignOnResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Sign-on response carrying the issued token in place of the COMMAREA.
 * Source      : No symbolic map exists; derived from
 *               app/cpy/COCOM01Y.cpy:25-28 identity fields +
 *               app/cbl/COSGN00C.cbl routing @ 7756d89
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
package com.cardemo.model.dto;

import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Size;

/**
 * Sign-on response payload: the identity that a successful authentication establishes, together
 * with the bearer token that replaces the COMMAREA handshake of the legacy sign-on program.
 *
 * <h2>Provenance: no BMS symbolic map exists for this type - "Not available"</h2>
 *
 * <p><strong>Not available.</strong> There is no BMS symbolic map for a sign-on response, so this
 * type is a genuinely new artefact rather than the translation of a map. That is a statement of
 * evidence, not an omission, and the reason is visible in the source rather than merely assumed:</p>
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COSGN00.CPY} does contain an output group,
 *       {@code 01 COSGN0AO REDEFINES COSGN0AI} at line 85, whose eleven {@code ...O} data items run
 *       from line 92 to line 152. That group is <em>not</em> a response payload: it is the sign-on
 *       screen re-displayed to the same terminal.</li>
 *   <li>{@code COSGN0AO} is populated and transmitted only by the {@code SEND-SIGNON-SCREEN}
 *       paragraph at {@code app/cbl/COSGN00C.cbl:145-154}, and that paragraph is performed only on
 *       first display (line 83) and on the four failure paths - a mapping-failure branch (line 94),
 *       a blank user identifier or password (lines 122 and 127), a wrong password (line 245) and an
 *       unknown user (line 251).</li>
 *   <li>On the <strong>success</strong> path at {@code app/cbl/COSGN00C.cbl:221-240} no map is sent
 *       at all. The program moves its results into the COMMAREA and transfers control with
 *       {@code EXEC CICS XCTL}. There was therefore never any success payload to lay out, which is
 *       precisely why no map for one exists.</li>
 * </ul>
 *
 * <p>Consequently the eleven-field list of {@code app/cpy-bms/COSGN00.CPY} is deliberately
 * <strong>not</strong> borrowed here; that map is the field contract of {@code SignOnRequest}. What
 * would be needed to source this type from a map is a mapset describing a successful sign-on reply,
 * and no such mapset exists anywhere in the corpus.</p>
 *
 * <p>What this type does derive from, field for field:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/COCOM01Y.cpy:25} - {@code 10 CDEMO-USER-ID PIC X(08).}, assigned at
 *       {@code app/cbl/COSGN00C.cbl:226} by {@code MOVE WS-USER-ID TO CDEMO-USER-ID}. This becomes
 *       the token subject claim and is carried here as {@link #userId()}.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:26-28} - {@code 10 CDEMO-USER-TYPE PIC X(01).} with its two
 *       condition names {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at line 27 and
 *       {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at line 28, assigned at
 *       {@code app/cbl/COSGN00C.cbl:227} by {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}. This
 *       becomes the role claim and is carried here as {@link #userType()}.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl} - the routing outcome of a successful sign-on, described
 *       below.</li>
 * </ul>
 *
 * <h2>The user type is load-bearing, not decorative</h2>
 *
 * <p>{@code app/cbl/COSGN00C.cbl:230-240} branches on the user type immediately after populating
 * the COMMAREA: when {@code CDEMO-USRTYP-ADMIN} holds it issues
 * {@code EXEC CICS XCTL PROGRAM('COADM01C')} to reach the administrative menu, and otherwise it
 * issues {@code EXEC CICS XCTL PROGRAM('COMEN01C')} to reach the main menu. The single character in
 * {@link #userType()} is therefore the value that decides where an authenticated caller goes next,
 * as well as the value the role claim is derived from. It is the one field in this payload whose
 * loss would change observable behaviour, which is why it is both preserved raw and offered in
 * typed form by {@link #resolvedUserType()}.</p>
 *
 * <h2>The token replaces the COMMAREA, and no session state survives</h2>
 *
 * <p>The legacy program ended every invocation with
 * {@code EXEC CICS RETURN TRANSID (WS-TRANID) COMMAREA (CARDDEMO-COMMAREA)} at
 * {@code app/cbl/COSGN00C.cbl:98-101}, handing the whole communication area back to CICS so that
 * the next pseudo-conversational turn could resume from it. The REST replacement is stateless: the
 * identity travels forward inside the token in {@link #token()} and <strong>no server-side session
 * state is retained</strong> between requests.</p>
 *
 * <p>Because of that, most of the COMMAREA has no counterpart in this payload, and the absences are
 * intentional:</p>
 *
 * <ul>
 *   <li>{@code CDEMO-FROM-TRANID PIC X(04)} at {@code app/cpy/COCOM01Y.cpy:21},
 *       {@code CDEMO-FROM-PROGRAM PIC X(08)} at line 22, {@code CDEMO-TO-TRANID PIC X(04)} at line
 *       23 and {@code CDEMO-TO-PROGRAM PIC X(08)} at line 24 - <strong>no counterpart.</strong>
 *       Navigation is expressed by URL, so a transaction identifier and a program name are no
 *       longer transported. Lines 224 and 225 of {@code app/cbl/COSGN00C.cbl} do populate the two
 *       {@code FROM} fields on success, and they are still dropped, because their only consumer was
 *       the program-to-program transfer that no longer happens.</li>
 *   <li>{@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code app/cpy/COCOM01Y.cpy:29}, with
 *       {@code 88 CDEMO-PGM-ENTER VALUE 0} at line 30 and {@code 88 CDEMO-PGM-REENTER VALUE 1} at
 *       line 31 - <strong>no counterpart.</strong> The enter-versus-re-enter flag existed only to
 *       tell a pseudo-conversational program which half of itself to run; a stateless request has
 *       no such halves. {@code app/cbl/COSGN00C.cbl:228} zeroes it on success, which the REST
 *       surface has nothing to represent.</li>
 *   <li>{@code CDEMO-LAST-MAP PIC X(7)} and {@code CDEMO-LAST-MAPSET PIC X(7)} at
 *       {@code app/cpy/COCOM01Y.cpy:43-44} - <strong>no counterpart.</strong> No screen state is
 *       retained because no screen is rendered.</li>
 * </ul>
 *
 * <h2>Security posture</h2>
 *
 * <p>This payload carries a bearer credential, so its surface is deliberately minimal and the
 * following properties are guarantees rather than conventions:</p>
 *
 * <ul>
 *   <li><strong>No password, in any form.</strong> Not the value the caller presented, not a hash of
 *       it and not a salt. A sign-on reply never returns the credential it was given. The plain-text
 *       {@code SEC-USR-PWD} field that the legacy comparison at {@code app/cbl/COSGN00C.cbl:223}
 *       read is hashed by the seed migration and is not represented anywhere in this package.</li>
 *   <li><strong>No signing key, and no reference to one.</strong> The key that signs the value in
 *       {@link #token()} is resolved from an environment variable by the security layer. That layer
 *       is not imported here and a key must never traverse a data transfer object.</li>
 *   <li><strong>No {@code Authorization} or {@code Bearer} header value.</strong> {@link #token()}
 *       holds the raw token only; scheme prefixing is the transport's concern, not this type's.</li>
 *   <li><strong>{@code toString()} never emits the token.</strong> See {@link #toString()} - the
 *       override is mandatory, not cosmetic.</li>
 *   <li><strong>Not {@code Serializable}, deliberately.</strong> Java serialisation of a
 *       token-bearing object is an insecure-deserialisation risk: the credential would travel in an
 *       opaque byte stream that no log masking inspects and that a gadget chain on the receiving
 *       side could exploit. {@code implements Serializable} must not be added later. Should some
 *       overriding reason ever demand it, a {@code private static final long serialVersionUID}
 *       must be declared in the same change, because the {@code serial} lint is fatal under
 *       {@code -Werror}.</li>
 *   <li><strong>No personally identifiable information.</strong> See the omissions below.</li>
 * </ul>
 *
 * <p>Note that, because this is a record, the compiler-generated {@code equals} and
 * {@code hashCode} are value-based across <em>all three</em> components and therefore do take the
 * token into account. That is correct value semantics and discloses nothing by itself, but it does
 * mean instances must not be used as cache keys, nor identified by their hash code in diagnostics.
 * The log masking in {@code logback-spring.xml} is a second line of defence only; the primary
 * defence is never emitting the token in the first place.</p>
 *
 * <h2>Surface deliberately omitted, and why</h2>
 *
 * <p>Each of the following was considered and rejected. Rule 1 Clause A puts correctness ahead of
 * convenience and Clause D requires least privilege, so surface with no source analogue is not
 * invented:</p>
 *
 * <ul>
 *   <li><em>Token expiry timestamp, issued-at timestamp, token type string, scope list and refresh
 *       token</em> - <strong>Not available.</strong> The source has no analogue for any of them:
 *       {@code app/cpy/COCOM01Y.cpy} declares no lifetime, no issue instant and no scope, and the
 *       legacy COMMAREA had no expiry concept whatsoever. Adding them would enlarge the credential
 *       surface and, in the case of a refresh token, add a second long-lived credential that
 *       nothing in the corpus requires. A client that needs an expiry can read the claim from the
 *       token it already holds.</li>
 *   <li><em>Customer first, middle and last name</em> - <strong>Not available</strong> as a sign-on
 *       result. {@code CDEMO-CUST-FNAME}, {@code CDEMO-CUST-MNAME} and {@code CDEMO-CUST-LNAME},
 *       each {@code PIC X(25)} at {@code app/cpy/COCOM01Y.cpy:34-36}, sit in the
 *       {@code CDEMO-CUSTOMER-INFO} group, and the success path at
 *       {@code app/cbl/COSGN00C.cbl:224-228} populates only the two {@code FROM} fields, the user
 *       identifier, the user type and the program context. It never writes the customer-name group,
 *       so those fields hold whatever the caller passed in and are not a sign-on result at all.
 *       Returning them would both invent a result the source does not produce and disclose
 *       personally identifiable information gratuitously.</li>
 *   <li><em>A routing hint naming the destination program</em> - omitted. {@code CDEMO-TO-PROGRAM}
 *       and {@code CDEMO-TO-TRANID} have no counterpart by design, as set out above, and the
 *       administrative-versus-main destination that {@code app/cbl/COSGN00C.cbl:230-240} selects is
 *       already fully determined by {@link #userType()}. Restating it as a second field would
 *       reintroduce a COMMAREA field that has been deliberately dropped, and would create two
 *       sources of truth for one decision.</li>
 *   <li><em>A width bound on {@link #token()}</em> - <strong>Not available.</strong> A token is a
 *       new artefact with no COBOL {@code PIC} clause, so there is no declared width to assert. A
 *       bound invented here would reject valid tokens as the claim set grows.</li>
 * </ul>
 *
 * <h2>Behaviour, validation and error modes</h2>
 *
 * <p>This is a pure data holder. It does not issue, parse, validate, sign or verify a token; it does
 * not decide routing; it compares nothing and normalises nothing. In particular it applies no
 * {@code trim()}, no {@code toUpperCase()} and no {@code toLowerCase()} on ingest, so a value is
 * transported exactly as supplied. Nothing in this type is locale-, charset- or time-zone-sensitive,
 * so its behaviour cannot vary with host defaults; any case folding elsewhere in the application is
 * required to use the {@code Locale.ROOT} overload. It holds no mutable static state and its two
 * constants are immutable primitives, so instances are immutable and inherently thread safe.</p>
 *
 * <p>Absent, blank and invalid remain three distinct states, as they do package-wide: {@code null}
 * means absent, an empty or all-blank value means blank, and an over-width value means invalid.
 * {@code null} is never coerced to {@code ""} and {@code ""} is never coerced to {@code null}. The
 * {@code jakarta.validation} bounds on {@link #userId()} and {@link #userType()} record the declared
 * COBOL widths so that a silent widening or truncation cannot pass unnoticed; consistent with the
 * three-state model they treat both {@code null} and the empty string as valid, because neither
 * absence nor blankness is a constraint violation.</p>
 *
 * <p>Error modes:</p>
 *
 * <ul>
 *   <li><em>Construction</em> - the canonical constructor validates nothing, normalises nothing and
 *       rejects nothing, so constructing this type throws no exception in normal operation. Any
 *       component may be {@code null}.</li>
 *   <li><em>Over-width {@code userId} or {@code userType}</em> - reported as a constraint violation
 *       only where this type is explicitly validated. The violation names the offending field and
 *       never echoes its value, which matters because one component of this type is a
 *       credential.</li>
 *   <li><em>An out-of-domain {@code userType}</em> - never an exception. {@link #resolvedUserType()}
 *       returns {@code null} instead, so the raw value round-trips rather than failing.</li>
 *   <li><em>Malformed JSON, or a component of the wrong JSON type</em> - rejected by the
 *       deserialiser before this type is constructed, and mapped to a client error by the web
 *       layer.</li>
 * </ul>
 *
 * <h2>Building and testing</h2>
 *
 * <p>This type needs no configuration and has no defaults beyond the null-preserving behaviour
 * described above. It compiles under {@code mvn -B clean compile} with {@code -Xlint:all -Werror}
 * and {@code failOnWarning}, so any warning it introduced would fail the build outright, and it is
 * exercised by the unit suite under {@code src/test/java/com/cardemo/unit/model} via
 * {@code mvn -B clean test}. Troubleshooting: if a token value is ever observed in a log line or in
 * any diagnostic rendering of this object, {@link #toString()} has been removed, weakened or
 * bypassed by a custom serialiser - that override, and not the log masking, is the primary
 * defence.</p>
 *
 * @param token  the issued JSON Web Token, in raw unprefixed form. No source field; new for the
 *               REST surface, replacing the {@code COMMAREA} that
 *               {@code app/cbl/COSGN00C.cbl:98-101} returned to CICS. Carries no scheme prefix, is
 *               never rendered by {@link #toString()}, and is neither parsed, validated nor signed
 *               by this type. May be {@code null}, which means absent.
 * @param userId the authenticated user identifier, the value that becomes the token subject claim.
 *               Source {@code CDEMO-USER-ID PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:25},
 *               assigned by {@code app/cbl/COSGN00C.cbl:226}. Bounded at
 *               {@value #USER_ID_MAX_LENGTH} characters and transported exactly as supplied. May be
 *               {@code null}, which means absent.
 * @param userType the raw one-character user type code, preserved as a {@code String} rather than
 *               bound to an enum so that an out-of-domain value round-trips instead of failing to
 *               bind. Source {@code CDEMO-USER-TYPE PIC X(01)} at {@code app/cpy/COCOM01Y.cpy:26},
 *               whose two condition names at lines 27 and 28 define {@code 'A'} and {@code 'U'},
 *               assigned by {@code app/cbl/COSGN00C.cbl:227}. Exactly
 *               {@value #USER_TYPE_LENGTH} character when present; see {@link #resolvedUserType()}
 *               for the typed view. May be {@code null}, which means absent.
 */
public record SignOnResponse(

        String token,

        @Size(max = USER_ID_MAX_LENGTH)
        String userId,

        @Size(max = USER_TYPE_LENGTH)
        String userType) {

    /**
     * Declared width of {@code userId}: {@code CDEMO-USER-ID PIC X(08)} at
     * {@code app/cpy/COCOM01Y.cpy:25}.
     *
     * <p>Stated as a named constant so that the field contract is asserted at its point of use and
     * cannot be silently widened or truncated. It is the width of the COBOL field, not a policy: no
     * minimum length and no character-class restriction is derived from it, because the source
     * imposes none.</p>
     */
    public static final int USER_ID_MAX_LENGTH = 8;

    /**
     * Declared width of {@code userType}: {@code CDEMO-USER-TYPE PIC X(01)} at
     * {@code app/cpy/COCOM01Y.cpy:26}.
     *
     * <p>One byte, which is why the component is a single-character {@code String} and why
     * {@link #resolvedUserType()} inspects exactly one character. The two values that byte may
     * meaningfully take are fixed by the condition names at lines 27 and 28 of the same
     * copybook.</p>
     */
    public static final int USER_TYPE_LENGTH = 1;

    /**
     * Returns the typed view of {@link #userType()}, or {@code null} when the raw code is not one of
     * the two the copybook defines.
     *
     * <p>The typed form is offered because the source itself types this field: the condition names
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at
     * {@code app/cpy/COCOM01Y.cpy:27-28} are exactly a two-constant enumeration, and
     * {@code app/cbl/COSGN00C.cbl:230} tests the first of them to choose between the administrative
     * and main menus. The raw component remains the wire contract; this accessor is a convenience
     * over it and is excluded from JSON, so the serialised shape stays exactly {@code token},
     * {@code userId} and {@code userType}.</p>
     *
     * <p><strong>Null contract.</strong> {@code null} is returned, and no exception is thrown, when
     * the raw component is {@code null}, is empty, is longer than one character - including a
     * one-character code padded with the blanks a fixed-width record carries - or is a single
     * character other than {@code 'A'} or {@code 'U'}. Returning {@code null} rather than throwing
     * is deliberate: an out-of-domain stored value must round-trip through this payload instead of
     * making it unreadable. Callers must therefore treat the result as nullable and must not assume
     * that a non-null {@link #userType()} implies a non-null result here.</p>
     *
     * <p>Resolution is <strong>case sensitive</strong>, which is parity behaviour rather than
     * strictness. {@code app/cbl/COSGN00C.cbl:132} and {@code :135} apply {@code FUNCTION
     * UPPER-CASE} to the entered user identifier and password only; line 227 then moves
     * {@code SEC-USR-TYPE} into {@code CDEMO-USER-TYPE} unfolded, and line 230 compares that byte
     * against {@code 'A'} exactly. A lower-case type byte satisfies neither condition name in the
     * legacy program, so it must satisfy neither here. Nothing is trimmed, folded or normalised, so
     * this method performs no locale-sensitive operation and cannot vary with the host's default
     * locale.</p>
     *
     * <p>This method is a pure function: it has no side effects, performs no I/O, mutates nothing,
     * never throws, and is safe for concurrent use. The mapping itself is delegated to
     * {@code UserType}, so the {@code 'A'} and {@code 'U'} literals are transcribed from the
     * copybook in exactly one place in the code base and cannot drift apart.</p>
     *
     * @return the matching {@code UserType} constant, or {@code null} if {@link #userType()} is
     *         {@code null}, is not exactly one character long, or is not one of the two codes
     *         defined at {@code app/cpy/COCOM01Y.cpy:27-28}
     */
    @JsonIgnore
    public UserType resolvedUserType() {
        return UserType.fromCode(this.userType).orElse(null);
    }

    /**
     * Returns a diagnostic rendering that deliberately omits the token.
     *
     * <p>Only {@code userId} and {@code userType} are reported. <strong>This override is mandatory
     * rather than cosmetic:</strong> the {@code toString()} that a record would otherwise inherit
     * prints every component, and would therefore print the bearer credential in
     * {@link #token()}. Leaking a token into a log is equivalent to leaking a password, because it
     * is directly replayable until it expires.</p>
     *
     * <p>Nothing is emitted in the token's place - not a truncation, not a masked prefix and not a
     * length - so this rendering discloses nothing whatsoever about the issued token, not even
     * whether one is present. The two fields that are reported are an opaque identifier and a
     * one-character role code; neither is personally identifiable, which is a further reason the
     * customer-name fields are absent from this type altogether.</p>
     *
     * @return a rendering of this response that contains no credential material and no personally
     *         identifiable information
     */
    @Override
    public String toString() {
        return "SignOnResponse[userId=" + this.userId + ", userType=" + this.userType + "]";
    }
}
