/*
 ******************************************************************
 * Program     : SignOnRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Sign-on request payload; password is write-only.
 * Source      : app/cpy-bms/COSGN00.CPY (11 fields) @ 7756d89
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * Sign-on request payload: the eleven input fields a client presents in order to authenticate, derived
 * field-for-field from the BMS symbolic map {@code app/cpy-bms/COSGN00.CPY}.
 *
 * <p>The symbolic map's input group {@code 01 COSGN0AI} opens at {@code app/cpy-bms/COSGN00.CPY:17} and runs
 * through line 84, where {@code 01 COSGN0AO REDEFINES COSGN0AI} takes over at line 85. Within that group BMS
 * generates a quintuple for every screen field: a {@code COMP PIC S9(4)} length item, an attribute byte, a
 * redefined attribute alias, four reserved bytes, and only then the data item itself. Only the eleven
 * {@code ...I} data items constitute the data-transfer budget; the surrounding plumbing and the leading
 * twelve-byte terminal input/output header at line 18 are terminal concerns and are deliberately not modelled.
 * The count is therefore <strong>exactly eleven</strong>, no more and no fewer, and the components below
 * preserve both the source order and the declared width of every one of them.
 *
 * <p>This map's {@code CURTIMEI} is nine bytes, not eight.
 * {@code app/cpy-bms/COSGN00.CPY:54} declares {@code 02 CURTIMEI PIC X(9).}, whereas the other sixteen symbolic
 * maps under {@code app/cpy-bms} - COACTUP, COACTVW, COADM01, COBIL00, COCRDLI, COCRDSL, COCRDUP, COMEN01,
 * CORPT00, COTRN00, COTRN01, COTRN02, COUSR00, COUSR01, COUSR02 and COUSR03 - each declare
 * {@code 02 CURTIMEI PIC X(8).} at line 54 exactly. COSGN00 is the sole nine-byte outlier in the corpus, so
 * {@code currentTime} is bounded at nine characters here and is never normalised down to eight to match its
 * siblings.
 *
 * <p>That single fact is also the reason <strong>no shared common-header type exists.</strong> Six
 * header fields genuinely do recur on all seventeen maps - {@code TRNNAME X(4)},
 * {@code TITLE01 X(40)}, {@code CURDATE X(8)}, {@code PGMNAME X(8)}, {@code TITLE02 X(40)} and
 * {@code CURTIME} - which invites extraction into a shared helper, base class, interface or mixin.
 * Such an abstraction would be factually wrong rather than merely redundant, because one inherited
 * {@code CURTIME} cannot carry nine bytes here and eight bytes everywhere else at the same time.
 * All six header fields are consequently declared inline in this record.</p>
 *
 * <p><strong>The password is write-only.</strong> {@code password} is annotated so that Jackson
 * accepts it when deserialising an inbound request body but never emits it when serialising this
 * type outward, so the credential cannot leak back through a response, an error payload or any
 * other rendering of this object. {@code toString()} is overridden for the same reason and reports
 * only {@code userId}, {@code transactionName} and {@code programName}; the override is mandatory
 * rather than cosmetic, because the implicit {@code toString()} a record would otherwise inherit
 * prints every component and would therefore print the credential. No password hash, JSON Web
 * Token, signing key or authorisation header value appears anywhere in this type: hashing and
 * verification belong to the security layer, which this package does not import.</p>
 *
 * <p><strong>This type is deliberately not {@code Serializable}.</strong> Java serialisation of a
 * credential-bearing object is an insecure-deserialisation risk: it would let the password travel
 * in an opaque byte stream that no log masking inspects and that any gadget-chain attack on the
 * receiving end could exploit. The omission is therefore a security decision and not an oversight,
 * so {@code implements Serializable} must not be added later. Note also that, because this is a
 * record, the compiler-generated {@code equals} and {@code hashCode} are value-based across all
 * eleven components and so do take the credential into account. That is correct value semantics and
 * discloses nothing by itself, but it does mean instances should not be used as cache keys or
 * identified in diagnostics by their hash code.</p>
 *
 * <p><strong>Case normalisation is the service's job, not this type's.</strong>
 * {@code app/cbl/COSGN00C.cbl} upper-cases <strong>both</strong> identifiers before comparing them:
 * line 132 applies {@code FUNCTION UPPER-CASE} to {@code USERIDI} and line 135 applies it to
 * {@code PASSWDI}, and the comparison against the stored credential then happens at line 223. The
 * password is upper-cased just as the user identifier is, which is easy to overlook. This record
 * performs none of that: it applies no {@code trim()}, no {@code toUpperCase()} and no
 * {@code toLowerCase()} on ingest, and transports whatever the caller supplied verbatim so that
 * {@code AuthenticationService}, the target of {@code app/cbl/COSGN00C.cbl}, can apply the rule
 * itself. When that service does apply it, it must use the {@code Locale.ROOT} overload rather than
 * the locale-sensitive one, so that the result does not vary with the host's default locale.</p>
 *
 * <p><strong>Absent, blank and invalid are three distinct states.</strong>
 * {@code app/cpy/CSSETATY.cpy} is a {@code COPY ... REPLACING} template copied into the
 * {@code PROCEDURE DIVISION} rather than a data layout, and its body at lines 18 to 27 models
 * exactly three outcomes per field: OK, NOT-OK and BLANK. BLANK is a state in its own right, not a
 * flavour of NOT-OK - it highlights the field and additionally stamps a literal {@code '*'} into
 * it - and the markers fire only on re-entry, never on first display. {@code app/cbl/COSGN00C.cbl}
 * reinforces this at lines 117 to 130, where {@code SPACES OR LOW-VALUES} is tested as a
 * first-class condition before any credential lookup is attempted. This record therefore preserves
 * all three states: a {@code null} component means absent, an empty or all-spaces component means
 * blank, and an over-width component means invalid. {@code null} is never coerced to {@code ""} and
 * {@code ""} is never coerced to {@code null}.</p>
 *
 * <p><strong>Validation and error modes.</strong> This type is an inbound payload and therefore a
 * trust boundary, so <em>every one</em> of the eleven components carries a {@code jakarta.validation}
 * {@code @Size} bound equal to its declared COBOL width - including {@code errorMessage}, which the
 * terminal could not populate but a REST client can. The bounds are deliberately no stricter
 * than the source: no minimum length, no character-class restriction and no password-complexity
 * rule is imposed, because rejecting input the legacy system accepts breaks parity just as surely
 * as accepting input it rejects. Consistent with the three-state model, {@code @Size} treats
 * {@code null} as valid and an empty string as valid, so neither absence nor blankness is reported
 * as a constraint violation; both are states the service interprets. Error modes are:</p>
 *
 * <ul>
 *   <li><em>Over-width component</em> - a constraint violation is raised at the validating
 *       boundary and surfaces as a client error. The violation names the offending field; it never
 *       echoes the offending value, which matters because one of these fields is a credential.</li>
 *   <li><em>Wrong JSON type for a component</em> - the deserialiser rejects the body before this
 *       type is constructed, and the web layer maps that to a client error.</li>
 *   <li><em>Absent or blank component</em> - not an error here. It is carried through as-is for
 *       the service to act on, mirroring lines 117 to 130 of {@code app/cbl/COSGN00C.cbl}.</li>
 * </ul>
 *
 * <p>The canonical constructor validates nothing, normalises nothing and rejects nothing, so
 * constructing this type throws no exception in normal operation. Enforcement is declarative and
 * happens at the boundary that validates it, which keeps this type a pure data holder with no
 * comparison, mapping, hashing or authentication logic and no mutable static state.</p>
 *
 * <p><strong>Building and testing.</strong> This type needs no configuration and has no defaults
 * beyond the null-preserving behaviour described above. It compiles under
 * {@code ./mvnw -B clean compile} with {@code -Xlint:all -Werror}, so any warning it introduced would
 * fail the build outright, and it is exercised by the unit suite under
 * {@code src/test/java/com/cardemo/unit/model} via {@code ./mvnw -B clean test}. Troubleshooting note:
 * if a response is ever observed to contain a {@code password} key, the write-only annotation on
 * that component has been removed or overridden by a custom serialiser. The annotation is the only
 * defence, not the first of two: no {@code logback-spring.xml} exists under
 * {@code src/main/resources} yet, so no log-side masking rule would catch the leak.</p>
 *
 * @param transactionName CICS transaction identifier shown in the screen header. Source
 *                        {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COSGN00.CPY:24}.
 * @param title01         first title line of the screen header. Source
 *                        {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COSGN00.CPY:30}.
 * @param currentDate     header date as rendered by the terminal, carried as presented rather than
 *                        parsed. Source {@code CURDATEI PIC X(8)} at
 *                        {@code app/cpy-bms/COSGN00.CPY:36}.
 * @param programName     name of the program that owns the screen. Source
 *                        {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:42}.
 * @param title02         second title line of the screen header. Source
 *                        {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COSGN00.CPY:48}.
 * @param currentTime     header time as rendered by the terminal, carried as presented rather than
 *                        parsed. Source {@code CURTIMEI PIC X(9)} at
 *                        {@code app/cpy-bms/COSGN00.CPY:54} - nine bytes, the sole outlier in the
 *                        corpus, as set out under Distinction 1 above.
 * @param applicationId   CICS application identifier of the region serving the request. Source
 *                        {@code APPLIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:60}.
 * @param systemId        CICS system identifier of the region serving the request. Source
 *                        {@code SYSIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:66}.
 * @param userId          user identifier offered for authentication, transported exactly as
 *                        supplied; the service upper-cases it. Source {@code USERIDI PIC X(8)} at
 *                        {@code app/cpy-bms/COSGN00.CPY:72}.
 * @param password        password offered for authentication, transported exactly as supplied; the
 *                        service upper-cases it too. Write-only: accepted on deserialisation, never
 *                        serialised outward, and never rendered by {@code toString()}. Source
 *                        {@code PASSWDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:78}.
 * @param errorMessage    diagnostic text the program writes back to the screen. It occupies the
 *                        input group, so it is part of the eleven-field budget. On the terminal the
 *                        program wrote it and the operator could not, but on a stateless REST surface
 *                        it binds from the request body like every other component, so it is bounded
 *                        at its declared width for the same reason the other ten are. Source
 *                        {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COSGN00.CPY:84}.
 */
public record SignOnRequest(

        @Size(max = TRANSACTION_NAME_MAX_LENGTH)
        String transactionName,

        @Size(max = TITLE01_MAX_LENGTH)
        String title01,

        @Size(max = CURRENT_DATE_MAX_LENGTH)
        String currentDate,

        @Size(max = PROGRAM_NAME_MAX_LENGTH)
        String programName,

        @Size(max = TITLE02_MAX_LENGTH)
        String title02,

        @Size(max = CURRENT_TIME_MAX_LENGTH)
        String currentTime,

        @Size(max = APPLICATION_ID_MAX_LENGTH)
        String applicationId,

        @Size(max = SYSTEM_ID_MAX_LENGTH)
        String systemId,

        @Size(max = USER_ID_MAX_LENGTH)
        String userId,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Size(max = PASSWORD_MAX_LENGTH)
        String password,

        @Size(max = ERROR_MESSAGE_MAX_LENGTH)
        String errorMessage) {

    /**
     * Declared width of {@code transactionName}: {@code TRNNAMEI PIC X(4)} at
     * {@code app/cpy-bms/COSGN00.CPY:24}.
     */
    public static final int TRANSACTION_NAME_MAX_LENGTH = 4;

    /**
     * Declared width of {@code title01}: {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COSGN00.CPY:30}.
     */
    public static final int TITLE01_MAX_LENGTH = 40;

    /**
     * Declared width of {@code currentDate}: {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:36}.
     */
    public static final int CURRENT_DATE_MAX_LENGTH = 8;

    /**
     * Declared width of {@code programName}: {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:42}.
     */
    public static final int PROGRAM_NAME_MAX_LENGTH = 8;

    /**
     * Declared width of {@code title02}: {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COSGN00.CPY:48}.
     */
    public static final int TITLE02_MAX_LENGTH = 40;

    /**
     * Declared width of {@code currentTime}: {@code CURTIMEI PIC X(9)} at {@code app/cpy-bms/COSGN00.CPY:54}.
     */
    public static final int CURRENT_TIME_MAX_LENGTH = 9;

    /**
     * Declared width of {@code applicationId}: {@code APPLIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:60}.
     */
    public static final int APPLICATION_ID_MAX_LENGTH = 8;

    /**
     * Declared width of {@code systemId}: {@code SYSIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:66}.
     */
    public static final int SYSTEM_ID_MAX_LENGTH = 8;

    /**
     * Declared width of {@code userId}: {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:72}.
     */
    public static final int USER_ID_MAX_LENGTH = 8;

    /**
     * Declared width of {@code password}: {@code PASSWDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:78}.
     */
    public static final int PASSWORD_MAX_LENGTH = 8;

    /**
     * Declared width of {@code errorMessage}: {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COSGN00.CPY:84}.
     */
    public static final int ERROR_MESSAGE_MAX_LENGTH = 78;

    /**
     * Returns a diagnostic rendering that deliberately omits the credential.
     *
     * @return a rendering of this request that contains no credential material
     */
    @Override
    public String toString() {
        return "SignOnRequest[userId=" + userId
                + ", transactionName=" + transactionName
                + ", programName=" + programName + "]";
    }

    /**
     * Rejects any JSON property that is not one of the eleven this type declares.
     *
     * <p>The symbolic map is a closed field contract: {@code 01 COSGN0AI} declares exactly eleven
     * input data items and the terminal could send nothing else. Silently discarding an unrecognised
     * property would break that contract in the direction that hides mistakes rather than reporting
     * them - a client that misspells {@code userId} would otherwise authenticate as an absent user and
     * receive the source's own "please enter User ID" path, which reads as a credential failure rather
     * than as the malformed request it is.</p>
     *
     * <p>This guard is declared on the type rather than configured on the object mapper, and that is
     * deliberate. A mapper-level setting is one line of configuration away from being switched off,
     * and this repository publishes no {@code application*.yml} at all, so the framework default -
     * which is to ignore unknown properties - would otherwise be in force. Declaring the guard here
     * means it holds under a lenient mapper as well as a strict one.</p>
     *
     * <p>Neither the offending name nor the offending value is echoed in the message. Both are
     * untrusted input, and echoing either would let a caller place chosen text into the logs of a
     * request that failed at the authentication boundary.</p>
     *
     * @param name  the unrecognised property name, accepted only so that Jackson can invoke this
     *              method; deliberately never read
     * @param value the unrecognised property value, accepted only so that Jackson can invoke this
     *              method; deliberately never read
     * @throws IllegalArgumentException always, because no unrecognised property is acceptable
     */
    @JsonAnySetter
    void rejectUnrecognisedProperty(final String name, final Object value) {
        throw new IllegalArgumentException(
                "SignOnRequest accepts only the 11 fields declared by app/cpy-bms/COSGN00.CPY, and an"
                        + " unrecognised property was supplied. The offending name and value are"
                        + " withheld because they are untrusted input.");
    }
}
